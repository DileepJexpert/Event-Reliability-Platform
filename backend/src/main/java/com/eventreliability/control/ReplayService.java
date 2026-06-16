package com.eventreliability.control;

import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.eventreliability.audit.AuditService;
import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.Incident;
import com.eventreliability.domain.MessageState;
import com.eventreliability.config.TopicNames;
import com.eventreliability.observability.PlatformMetrics;
import com.eventreliability.routing.ParkingService;
import com.eventreliability.security.PayloadProtectionService;
import com.eventreliability.state.StateService;
import com.eventreliability.streams.ReadModels;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Consumer side of the control plane (§13): executes replay/quarantine commands. Replay reconstructs
 * the original message from the retained payload + headers and republishes it to the source topic
 * (the platform re-drives — it does not run app logic). Every execution is audited with the acting
 * user, so "who clicked bulk-replay" is as auditable as the failures themselves (§17).
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final ReliabilityProperties props;
    private final TopicNames topics;
    private final KafkaPublisher publisher;
    private final StateService stateService;
    private final ReadModels readModels;
    private final AuditService auditService;
    private final ParkingService parkingService;
    private final PlatformMetrics metrics;
    private final PayloadProtectionService payloadProtection;

    public ReplayService(ReliabilityProperties props, TopicNames topics, KafkaPublisher publisher,
                         StateService stateService, ReadModels readModels, AuditService auditService,
                         ParkingService parkingService, PlatformMetrics metrics,
                         PayloadProtectionService payloadProtection) {
        this.props = props;
        this.topics = topics;
        this.publisher = publisher;
        this.stateService = stateService;
        this.readModels = readModels;
        this.auditService = auditService;
        this.parkingService = parkingService;
        this.metrics = metrics;
        this.payloadProtection = payloadProtection;
    }

    public void replaySingle(String correlationId, String actor, String reason,
                             String targetTopic, String payloadOverrideBase64) {
        FailureRecord record = stateService.find(correlationId).orElse(null);
        if (record == null) {
            log.warn("Replay command for unknown correlation id {}", correlationId);
            return;
        }
        if (doReplay(record, actor, reason != null ? reason : "single replay", targetTopic, payloadOverrideBase64)) {
            metrics.replay("single");
        }
    }

    public void bulkReplay(String incidentId, String actor, String reason, String targetTopic) {
        Incident incident = readModels.incident(incidentId).orElse(null);
        if (incident == null) {
            log.warn("Bulk-replay command for unknown incident {}", incidentId);
            return;
        }
        String signature = incident.rootCause();
        // Sort the cohort by (original topic, original key, original offset) so re-driven messages on
        // the same key are republished in source-offset order — preserving per-key ordering on the
        // destination partition for order-sensitive flows (e.g. ledger debit-before-credit).
        List<FailureRecord> cohort = readModels.allFailures().stream()
                .filter(r -> signature.equals(r.rootCauseSignature()))
                .filter(r -> r.state() != MessageState.RESOLVED && r.state() != MessageState.REPLAYED)
                .sorted(BULK_REPLAY_ORDER)
                .limit(props.replay().bulkBatchSize())
                .toList();

        int replayed = 0;
        for (FailureRecord record : cohort) {
            if (doReplay(record, actor, "bulk-replay of incident " + incidentId, targetTopic, null)) {
                replayed++;
            }
        }
        if (replayed > 0) {
            metrics.replay("bulk");
        }

        // Mark the incident resolved in the compacted view, and audit the bulk action at incident level.
        publisher.sendJson(topics.viewsIncidents(), incidentId, incident.resolved());
        auditService.record(incidentId, null, MessageState.BULK_REPLAY_APPROVED, "BULK_REPLAYED", actor,
                "replayed " + replayed + " message(s) for " + signature
                        + (reason != null ? ": " + reason : ""),
                Map.of("incidentId", incidentId, "count", Integer.toString(replayed)));
        log.info("Bulk-replayed {} message(s) for incident {} by {}", replayed, incidentId, actor);
    }

    public void quarantine(String correlationId, String actor, String reason) {
        FailureRecord record = stateService.find(correlationId).orElse(null);
        if (record == null) {
            log.warn("Quarantine command for unknown correlation id {}", correlationId);
            return;
        }
        parkingService.quarantineByUser(record, payloadProtection.decryptForReplay(record.payloadBase64()),
                FailureHeaders.fromMap(record.headers()), actor,
                reason != null ? reason : "manually quarantined");
    }

    private boolean doReplay(FailureRecord record, String actor, String detail,
                             String targetTopic, String payloadOverrideBase64) {
        String destination = (targetTopic != null && !targetTopic.isBlank())
                ? targetTopic : record.originalTopic();
        if (destination == null || destination.isBlank()) {
            auditService.userAction(record.correlationId(), record.state(), record.state(),
                    "REPLAY_FAILED", actor, "cannot replay — no original/target topic known");
            return false;
        }
        Headers out = FailureHeaders.fromMap(record.headers());
        out.remove(FailureHeaders.ELIGIBLE_AT);
        out.remove(FailureHeaders.RETRY_TIER);
        String key = FailureHeaders.getString(out, FailureHeaders.ORIGINAL_KEY);

        boolean edited = payloadOverrideBase64 != null && !payloadOverrideBase64.isBlank();
        byte[] payload = edited ? decode(payloadOverrideBase64) : payloadProtection.decryptForReplay(record.payloadBase64());
        publisher.send(new ProducerRecord<>(destination, null, key, payload, out));
        stateService.put(record.toBuilder().state(MessageState.REPLAYED).lastActor(actor).build());
        auditService.userAction(record.correlationId(), record.state(), MessageState.REPLAYED,
                "REPLAYED", actor, detail + " → " + destination + (edited ? " (payload corrected)" : ""));
        return true;
    }

    private static byte[] decode(String base64) {
        return base64 == null ? null : Base64.getDecoder().decode(base64);
    }

    private static String originalKey(FailureRecord r) {
        return r == null || r.headers() == null ? null : r.headers().get(FailureHeaders.ORIGINAL_KEY);
    }

    private static final Comparator<FailureRecord> BULK_REPLAY_ORDER = Comparator
            .comparing(FailureRecord::originalTopic, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ReplayService::originalKey, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(r -> r.originalOffset() == null ? Long.MAX_VALUE : r.originalOffset());
}
