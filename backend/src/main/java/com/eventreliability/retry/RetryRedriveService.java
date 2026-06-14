package com.eventreliability.retry;

import com.eventreliability.audit.AuditService;
import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.CorrelationIdResolver;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.ingestion.FailureRecordFactory;
import com.eventreliability.observability.PlatformMetrics;
import com.eventreliability.routing.ParkingService;
import com.eventreliability.state.StateService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Re-drives a now-eligible message back to the owning consumer (§10). The platform does <em>not</em>
 * run app logic — it republishes the original opaque payload (and headers) to the source topic (the
 * message's {@code x-original-topic}, or a configured fixed retry-input topic), and the owning
 * consumer's existing handler reprocesses it.
 *
 * <p>The platform is the single brain counting attempts: the outgoing message carries an incremented
 * {@code x-attempt-count}, so if it fails again and is republished to the inbound topic, the next
 * scheduling escalates to the next tier. Internal scheduling headers ({@code x-eligible-at},
 * {@code x-retry-tier}) are stripped so they don't leak to the source. Onboarded consumers must be
 * idempotent (§10/§18.3): a re-drive may duplicate a message that actually succeeded.
 */
@Service
public class RetryRedriveService {

    private static final Logger log = LoggerFactory.getLogger(RetryRedriveService.class);

    private final ReliabilityProperties props;
    private final CorrelationIdResolver correlationIds;
    private final KafkaPublisher publisher;
    private final StateService stateService;
    private final AuditService auditService;
    private final FailureRecordFactory recordFactory;
    private final ParkingService parkingService;
    private final PlatformMetrics metrics;

    public RetryRedriveService(ReliabilityProperties props, CorrelationIdResolver correlationIds,
                               KafkaPublisher publisher, StateService stateService, AuditService auditService,
                               FailureRecordFactory recordFactory, ParkingService parkingService,
                               PlatformMetrics metrics) {
        this.props = props;
        this.correlationIds = correlationIds;
        this.publisher = publisher;
        this.stateService = stateService;
        this.auditService = auditService;
        this.recordFactory = recordFactory;
        this.parkingService = parkingService;
        this.metrics = metrics;
    }

    public void redrive(ConsumerRecord<String, byte[]> record) {
        Headers h = record.headers();
        String correlationId = record.key() != null
                ? record.key()
                : correlationIds.fromHeaders(h);
        if (correlationId == null || correlationId.isBlank()) {
            log.warn("Dropping retry message with no correlation id on topic {}", record.topic());
            return;
        }

        FailureRecord existing = stateService.find(correlationId).orElse(null);
        FailureRecord base = existing != null
                ? existing
                : recordFactory.fromMessage(correlationId, h, record.value()).build();

        int attempt = FailureHeaders.getInt(h, FailureHeaders.ATTEMPT_COUNT, base.attemptCount());
        int nextAttempt = attempt + 1;
        String tier = FailureHeaders.getString(h, FailureHeaders.RETRY_TIER);

        String destination = destination(h);
        if (destination == null || destination.isBlank()) {
            parkingService.parkExhausted(base, record.value(), h,
                    "cannot re-drive — no destination/original topic known");
            return;
        }

        Headers out = FailureHeaders.copy(h);
        out.remove(FailureHeaders.ELIGIBLE_AT);
        out.remove(FailureHeaders.RETRY_TIER);
        FailureHeaders.put(out, FailureHeaders.ATTEMPT_COUNT, Integer.toString(nextAttempt));
        String key = FailureHeaders.getString(h, FailureHeaders.ORIGINAL_KEY);

        publisher.send(new ProducerRecord<>(destination, null, key, record.value(), out));

        FailureRecord updated = base.toBuilder()
                .state(MessageState.RETRYING)
                .attemptCount(nextAttempt)
                .currentTier(tier)
                .eligibleAt(null)
                .lastError(null)
                .build();
        stateService.put(updated);
        metrics.retryRedriven();
        auditService.system(correlationId,
                existing != null ? existing.state() : MessageState.RETRY_SCHEDULED, MessageState.RETRYING,
                "REDRIVEN", "re-driven to " + destination + " as attempt " + nextAttempt
                        + (tier != null ? " (tier " + tier + ")" : ""));
        log.info("Re-drove {} -> topic {} as attempt {}", correlationId, destination, nextAttempt);
    }

    private String destination(Headers h) {
        String mode = props.replay().destination();
        if (mode == null || mode.isBlank() || "original".equalsIgnoreCase(mode)) {
            return FailureHeaders.getString(h, FailureHeaders.ORIGINAL_TOPIC);
        }
        return mode;
    }
}
