package com.eventreliability.routing;

import java.util.Map;

import com.eventreliability.audit.AuditService;
import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.AuditEvent;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Terminal-lane remediation (§7.1): routes business failures to their owner and parks/quarantines
 * everything that must not be retried. Each method publishes the original message (payload + headers
 * preserved) to the correct terminal topic, advances the compacted state to the matching terminal
 * lifecycle state, and writes the audit entry. Shared by routing (§7.1) and the retry scheduler
 * (exhaustion, §10).
 */
@Service
public class ParkingService {

    private static final Logger log = LoggerFactory.getLogger(ParkingService.class);

    private final TopicNames topics;
    private final KafkaPublisher publisher;
    private final com.eventreliability.state.StateService stateService;
    private final AuditService auditService;

    public ParkingService(TopicNames topics, KafkaPublisher publisher,
                          com.eventreliability.state.StateService stateService, AuditService auditService) {
        this.topics = topics;
        this.publisher = publisher;
        this.stateService = stateService;
        this.auditService = auditService;
    }

    /** Business failure: route to the owner with a reason; never retry (§7.1). */
    public void routeBusiness(FailureRecord record, byte[] payload, Headers original) {
        String reason = record.reason() != null ? record.reason() : "business rejection";
        Headers h = FailureHeaders.copy(original);
        FailureHeaders.put(h, FailureHeaders.CLASSIFICATION, "BUSINESS");
        FailureHeaders.put(h, "x-business-reason", reason);
        publisher.send(new ProducerRecord<>(topics.businessRouted(), null, record.correlationId(), payload, h));

        FailureRecord updated = record.toBuilder().state(MessageState.ROUTED_BUSINESS).reason(reason).build();
        stateService.put(updated);
        auditService.system(record.correlationId(), record.state(), MessageState.ROUTED_BUSINESS,
                "ROUTED_BUSINESS", "routed to owner: " + reason);
        log.info("Routed business failure {} to owner: {}", record.correlationId(), reason);
    }

    /** Poison message: quarantine immediately (§7.1). */
    public void quarantinePoison(FailureRecord record, byte[] payload, Headers original) {
        toParked(record, payload, original, MessageState.QUARANTINED_POISON, "QUARANTINED",
                record.reason() != null ? record.reason() : "poison message quarantined",
                AuditEvent.SYSTEM_ACTOR);
    }

    /** UNKNOWN classification: park for human review (§7.1). */
    public void parkUnknown(FailureRecord record, byte[] payload, Headers original) {
        toParked(record, payload, original, MessageState.PARKED_UNKNOWN, "PARKED",
                record.reason() != null ? record.reason() : "unclassified — needs human review",
                AuditEvent.SYSTEM_ACTOR);
    }

    /** All retry tiers exhausted: park for human review (§10). */
    public void parkExhausted(FailureRecord record, byte[] payload, Headers original, String detail) {
        toParked(record, payload, original, MessageState.EXHAUSTED_PARKED, "EXHAUSTED", detail,
                AuditEvent.SYSTEM_ACTOR);
    }

    /** Operator-driven quarantine from the console (§13). */
    public void quarantineByUser(FailureRecord record, byte[] payload, Headers original, String actor, String reason) {
        toParked(record, payload, original, MessageState.QUARANTINED_POISON, "QUARANTINED_BY_USER",
                reason != null ? reason : "manually quarantined", actor);
    }

    private void toParked(FailureRecord record, byte[] payload, Headers original,
                          MessageState terminalState, String action, String detail, String actor) {
        Headers h = FailureHeaders.copy(original);
        FailureHeaders.put(h, FailureHeaders.CLASSIFICATION,
                record.classification() == null ? "UNKNOWN" : record.classification().name());
        publisher.send(new ProducerRecord<>(topics.parked(), null, record.correlationId(), payload, h));

        FailureRecord updated = record.toBuilder()
                .state(terminalState)
                .reason(detail)
                .lastActor(AuditEvent.SYSTEM_ACTOR.equals(actor) ? record.lastActor() : actor)
                .build();
        stateService.put(updated);
        auditService.record(record.correlationId(), record.state(), terminalState, action, actor, detail, Map.of());
        log.info("Parked {} as {} : {}", record.correlationId(), terminalState, detail);
    }
}
