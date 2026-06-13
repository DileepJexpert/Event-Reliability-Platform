package com.eventreliability.ingestion;

import java.util.UUID;

import com.eventreliability.audit.AuditService;
import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.observability.PlatformMetrics;
import com.eventreliability.state.StateService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Ingestion (§7.1 step 1). Consumes {@code reliability.failures.inbound}, records the message as
 * {@link MessageState#RECEIVED} in the compacted state topic (capturing headers + opaque payload for
 * later replay), appends an audit entry, and forwards the message — headers and payload preserved —
 * to the internal classification topic, keyed by correlation id.
 *
 * <p>Classification is deliberately kept off this consume loop (forwarded to its own topic) so a
 * future heavy/LLM classifier cannot throttle ingestion throughput (§11).
 */
@Component
public class FailureIngestionListener {

    private static final Logger log = LoggerFactory.getLogger(FailureIngestionListener.class);

    private final FailureRecordFactory recordFactory;
    private final StateService stateService;
    private final AuditService auditService;
    private final KafkaPublisher publisher;
    private final TopicNames topics;
    private final PlatformMetrics metrics;

    public FailureIngestionListener(FailureRecordFactory recordFactory, StateService stateService,
                                    AuditService auditService, KafkaPublisher publisher, TopicNames topics,
                                    PlatformMetrics metrics) {
        this.recordFactory = recordFactory;
        this.stateService = stateService;
        this.auditService = auditService;
        this.publisher = publisher;
        this.topics = topics;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "#{@topicNames.inbound()}", id = "ingestion")
    public void onInboundFailure(ConsumerRecord<String, byte[]> record) {
        Headers h = record.headers();
        log.info("RECV <- topic={} key={} partition={} offset={}", record.topic(), record.key(),
                record.partition(), record.offset());

        String correlationId = FailureHeaders.getString(h, FailureHeaders.CORRELATION_ID);
        boolean synthesized = correlationId == null || correlationId.isBlank();
        if (synthesized) {
            // Contract violation: keep the data rather than drop it; flag it on the audit trail.
            correlationId = "gen-" + UUID.randomUUID();
        }

        FailureRecord existing = stateService.find(correlationId).orElse(null);
        FailureRecord.Builder b = recordFactory.fromMessage(correlationId, h, record.value())
                .state(MessageState.RECEIVED);
        if (existing != null && existing.createdAt() != null) {
            b.createdAt(existing.createdAt());
        }
        FailureRecord rec = b.build();

        stateService.put(rec);
        auditService.system(correlationId,
                existing == null ? null : existing.state(), MessageState.RECEIVED, "INGESTED",
                synthesized ? "received on inbound (correlation id synthesized — header absent)"
                        : "received on inbound failure topic");

        // Forward to async classification, preserving original headers and opaque payload.
        publisher.send(new ProducerRecord<>(topics.classify(), null, correlationId,
                record.value(), record.headers()));

        metrics.ingested();
        log.debug("Ingested failure {} (attempt {}) from {}", correlationId, rec.attemptCount(),
                rec.originalTopic());
    }
}
