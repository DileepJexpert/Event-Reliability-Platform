package com.eventreliability.query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.eventreliability.api.dto.ExposureDto;
import com.eventreliability.api.dto.ExposureGroup;
import com.eventreliability.api.dto.ExposureItem;
import com.eventreliability.common.JsonCodec;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.ownership.OwnershipService;
import com.eventreliability.security.PayloadProtectionService;
import com.eventreliability.streams.ReadModels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Computes financial exposure ("value at risk", §16): the total business amount tied up in stuck
 * (un-recovered) failures. For each at-risk failure it decrypts the payload server-side, parses it as
 * JSON, extracts a configured amount + currency field, and sums per currency / team / topic. Only the
 * aggregated figures leave the service — raw payloads never do, so this stays read-only and safe.
 */
@Service
public class ExposureService {

    private static final int TOP_N = 20;
    /** States considered recovered — not money at risk. Everything else is still stuck. */
    private static final Set<MessageState> RECOVERED = EnumSet.of(MessageState.RESOLVED, MessageState.REPLAYED);

    private final ReadModels readModels;
    private final OwnershipService ownership;
    private final PayloadProtectionService payloadProtection;
    private final ReliabilityProperties.Exposure cfg;
    private final ObjectMapper mapper;

    public ExposureService(ReadModels readModels, OwnershipService ownership,
                           PayloadProtectionService payloadProtection, ReliabilityProperties props,
                           JsonCodec json) {
        this.readModels = readModels;
        this.ownership = ownership;
        this.payloadProtection = payloadProtection;
        this.cfg = props.exposure();
        this.mapper = json.mapper();
    }

    public ExposureDto compute() {
        Map<String, BigDecimal> totalByCcy = new TreeMap<>();
        Map<String, Accumulator> byTeam = new LinkedHashMap<>();
        Map<String, Accumulator> byTopic = new LinkedHashMap<>();
        List<ExposureItem> items = new ArrayList<>();

        long atRiskCount = 0;
        long withoutAmount = 0;
        long considered = 0;
        Long oldest = null;

        for (FailureRecord r : readModels.allFailures()) {
            if (r.state() != null && RECOVERED.contains(r.state())) {
                continue; // recovered -> not at risk
            }
            considered++;
            if (r.firstFailedAt() != null && (oldest == null || r.firstFailedAt() < oldest)) {
                oldest = r.firstFailedAt();
            }

            Amount amount = extractAmount(r);
            if (amount == null) {
                withoutAmount++;
                continue;
            }
            atRiskCount++;
            String team = ownership.teamFor(r);
            String topic = r.originalTopic() == null ? "(unknown)" : r.originalTopic();

            totalByCcy.merge(amount.currency(), amount.value(), BigDecimal::add);
            byTeam.computeIfAbsent(team, k -> new Accumulator()).add(amount);
            byTopic.computeIfAbsent(topic, k -> new Accumulator()).add(amount);
            items.add(new ExposureItem(r.correlationId(), amount.value().doubleValue(), amount.currency(),
                    team, topic, r.state() == null ? null : r.state().name(), r.firstFailedAt()));
        }

        items.sort(Comparator.comparingDouble(ExposureItem::amount).reversed());
        List<ExposureItem> top = items.size() > TOP_N ? items.subList(0, TOP_N) : items;

        return new ExposureDto(
                toDoubleMap(totalByCcy), atRiskCount, withoutAmount, considered,
                groups(byTeam), groups(byTopic), List.copyOf(top), oldest, System.currentTimeMillis());
    }

    private List<ExposureGroup> groups(Map<String, Accumulator> src) {
        return src.entrySet().stream()
                .map(e -> new ExposureGroup(e.getKey(), e.getValue().count, toDoubleMap(e.getValue().byCcy)))
                .sorted(Comparator.comparingDouble((ExposureGroup g) -> total(g.amountByCurrency())).reversed())
                .toList();
    }

    private static double total(Map<String, Double> m) {
        double t = 0;
        for (double v : m.values()) {
            t += v;
        }
        return t;
    }

    private static Map<String, Double> toDoubleMap(Map<String, BigDecimal> m) {
        Map<String, Double> out = new TreeMap<>();
        m.forEach((k, v) -> out.put(k, v.setScale(2, RoundingMode.HALF_UP).doubleValue()));
        return out;
    }

    private Amount extractAmount(FailureRecord r) {
        JsonNode root = parse(r);
        if (root == null) {
            return null;
        }
        BigDecimal value = null;
        for (String field : cfg.amountFields()) {
            JsonNode n = at(root, field);
            if (n == null || n.isMissingNode() || n.isNull()) {
                continue;
            }
            if (n.isNumber()) {
                value = n.decimalValue();
                break;
            }
            if (n.isTextual()) {
                try {
                    value = new BigDecimal(n.asText().trim().replace(",", ""));
                    break;
                } catch (NumberFormatException ignored) {
                    // try the next candidate field
                }
            }
        }
        return value == null ? null : new Amount(value, currency(root));
    }

    private String currency(JsonNode root) {
        for (String field : cfg.currencyFields()) {
            JsonNode n = at(root, field);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return n.asText().trim().toUpperCase();
            }
        }
        return cfg.defaultCurrency();
    }

    private JsonNode parse(FailureRecord r) {
        if (r.payloadBase64() == null) {
            return null;
        }
        try {
            byte[] raw = payloadProtection.decryptForReplay(r.payloadBase64());
            return raw == null ? null : mapper.readTree(raw);
        } catch (Exception ex) {
            return null; // not JSON / undecryptable -> counted as "without amount"
        }
    }

    /** Walk a dot-path (e.g. {@code transaction.amount}) from the root, returning the node or null. */
    private static JsonNode at(JsonNode root, String dotted) {
        JsonNode cur = root;
        for (String part : dotted.split("\\.")) {
            if (cur == null) {
                return null;
            }
            cur = cur.get(part);
        }
        return cur;
    }

    private record Amount(BigDecimal value, String currency) {
    }

    private static final class Accumulator {
        private long count;
        private final Map<String, BigDecimal> byCcy = new TreeMap<>();

        void add(Amount a) {
            count++;
            byCcy.merge(a.currency(), a.value(), BigDecimal::add);
        }
    }
}
