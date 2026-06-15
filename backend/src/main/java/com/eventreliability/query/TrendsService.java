package com.eventreliability.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.eventreliability.api.dto.TrendsDto;
import com.eventreliability.api.dto.TrendsDto.DailyCount;
import com.eventreliability.api.dto.TrendsDto.NameCount;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.streams.ReadModels;

import org.springframework.stereotype.Service;

/**
 * Computes the Trends tab's analytics (§16) on demand from the current state read model — no DB, no
 * extra Kafka topology. It's a snapshot over the compacted state (the live set of failures), bucketed
 * and aggregated in-process, consistent with how {@link FailureQueryService} serves the other reads.
 */
@Service
public class TrendsService {

    private static final int TOP_N = 8;
    private static final int DAILY_DAYS = 14;

    private final ReadModels readModels;

    public TrendsService(ReadModels readModels) {
        this.readModels = readModels;
    }

    public TrendsDto compute() {
        List<FailureRecord> all = readModels.allFailures();
        long total = all.size();
        long open = all.stream().filter(r -> !r.isTerminal()).count();
        long resolved = all.stream().filter(TrendsService::isRecovered).count();
        long parked = all.stream()
                .filter(r -> r.state() != null && MessageState.PARKED_STATES.contains(r.state()))
                .count();
        double resolutionRate = total == 0 ? 0.0 : (double) resolved / total;

        return new TrendsDto(
                total, open, resolved, parked, resolutionRate, meanTimeToResolution(all),
                countBy(all, r -> r.classification() == null ? null : r.classification().name()),
                countBy(all, r -> r.state() == null ? null : r.state().name()),
                top(all, FailureRecord::originalTopic),
                top(all, FailureRecord::sourceApp),
                daily(all));
    }

    private static boolean isRecovered(FailureRecord r) {
        return r.state() == MessageState.RESOLVED || r.state() == MessageState.REPLAYED;
    }

    /** Count by a string key (blank/null keys ignored), ordered by count descending. */
    private static Map<String, Long> countBy(List<FailureRecord> all, Function<FailureRecord, String> key) {
        Map<String, Long> counts = new HashMap<>();
        for (FailureRecord r : all) {
            String k = key.apply(r);
            if (k != null && !k.isBlank()) {
                counts.merge(k, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private static List<NameCount> top(List<FailureRecord> all, Function<FailureRecord, String> key) {
        return countBy(all, key).entrySet().stream()
                .limit(TOP_N)
                .map(e -> new NameCount(e.getKey(), e.getValue()))
                .toList();
    }

    /** Mean (updatedAt - firstFailedAt) over recovered failures; null when none qualify. */
    private static Long meanTimeToResolution(List<FailureRecord> all) {
        long sum = 0;
        long n = 0;
        for (FailureRecord r : all) {
            if (isRecovered(r) && r.firstFailedAt() != null && r.updatedAt() != null
                    && r.updatedAt() >= r.firstFailedAt()) {
                sum += r.updatedAt() - r.firstFailedAt();
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    /** Failures-per-day for the last {@link #DAILY_DAYS} UTC days (zero-filled, oldest first). */
    private static List<DailyCount> daily(List<FailureRecord> all) {
        LocalDate today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate start = today.minusDays(DAILY_DAYS - 1L);
        Map<LocalDate, Long> counts = new HashMap<>();
        for (FailureRecord r : all) {
            Long ts = r.firstFailedAt() != null ? r.firstFailedAt() : r.createdAt();
            if (ts == null) {
                continue;
            }
            LocalDate day = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).toLocalDate();
            if (!day.isBefore(start) && !day.isAfter(today)) {
                counts.merge(day, 1L, Long::sum);
            }
        }
        List<DailyCount> out = new ArrayList<>(DAILY_DAYS);
        for (int i = 0; i < DAILY_DAYS; i++) {
            LocalDate day = start.plusDays(i);
            out.add(new DailyCount(day.toString(), counts.getOrDefault(day, 0L)));
        }
        return out;
    }
}
