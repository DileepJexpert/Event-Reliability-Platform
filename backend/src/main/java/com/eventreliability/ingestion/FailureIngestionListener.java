package com.eventreliability.ingestion;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.eventreliability.audit.AuditService;
import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.domain.RootCauseSignature;
import com.eventreliability.state.StateService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Ingestion (§7.1 step 1). Consumes {@code reliability.failures.inbound}, records the message as
 * {@link MessageState#RECEIVED} in the compacted state topic (capturing headers + opaque payload for
 * later replay), appends an audit entry, and forwards the message — headers and payload preserved —
 * to the internal classification topic.
 *
 * <p>Classification is deliberately kept off this consume loop (forwarded to its own topic) so a
 * future heavy/LLM classifier cannot throttle ingestion throughput (§11). This listener uses the
 * default record-ack container factory; offset commit after the method returns is fine because the
 * state write + forward are idempotent on the correlation-id key.
 */
@Component
public class FailureIngestionListener {

    private static final Logger log = LoggerFactory.getLogger(FailureIngestionListener.class);

    private final StateService stateService;
    private final AuditService auditService;
    private final KafkaPublisher publisher;
    private final TopicNames topics;

    public FailureIngestionListener(StateService stateService, AuditService auditService,
                                    KafkaPublisher publisher, TopicNames topics) {
        this.stateService = stateService;
        this.auditService = auditService;
        this.publisher = publisher;
        this.topics = topics;
    }

    @KafkaListener(topics = "#{@topicNames.inbound()}", id = "ingestion")
    public void onInboundFailure(ConsumerRecord<String, byte[]> record) {
        Headers h = record.headers();

        String correlationId = FailureHeaders.getString(h, FailureHeaders.CORRELATION_ID);
        boolean synthesized = correlationId == null || correlationId.isBlank();
        if (synthesized) {
            // Contract violation: keep the data rather than drop it; flag it on the audit trail.
            correlationId = "gen-" + UUID.randomUUID();
        }

        FailureRecord existing = stateService.find(correlationId).orElse(null);
        long now = System.currentTimeMillis();

        FailureRecord.Builder b = (existing == null ? FailureRecord.builder() : existing.toBuilder());
        b.correlationId(correlationId)
                .state(MessageState.RECEIVED)
                .originalTopic(FailureHeaders.getString(h, FailureHeaders.ORIGINAL_TOPIC))
                .originalPartition(intOrNull(h, FailureHeaders.ORIGINAL_PARTITION))
                .originalOffset(longOrNull(h, FailureHeaders.ORIGINAL_OFFSET))
                .exceptionClass(FailureHeaders.getString(h, FailureHeaders.EXCEPTION_CLASS))
                .exceptionMessage(FailureHeaders.getString(h, FailureHeaders.EXCEPTION_MESSAGE))
                .stacktrace(FailureHeaders.getString(h, FailureHeaders.STACKTRACE))
                .attemptCount(FailureHeaders.getInt(h, FailureHeaders.ATTEMPT_COUNT,
                        existing != null ? existing.attemptCount() : 0))
                .firstFailedAt(FailureHeaders.getLong(h, FailureHeaders.FIRST_FAILED_AT,
                        existing != null && existing.firstFailedAt() != null ? existing.firstFailedAt() : now))
                .sourceApp(FailureHeaders.getString(h, FailureHeaders.SOURCE_APP))
                .schemaVersion(FailureHeaders.getString(h, FailureHeaders.SCHEMA_VERSION))
                .payloadHash(FailureHeaders.getString(h, FailureHeaders.PAYLOAD_HASH))
                .payloadBase64(record.value() == null ? null
                        : Base64.getEncoder().encodeToString(record.value()))
                .headers(headerMap(h));

        FailureRecord rec = b.build();
        rec = rec.toBuilder().rootCauseSignature(RootCauseSignature.of(rec)).build();

        stateService.put(rec);
        auditService.system(correlationId,
                existing == null ? null : existing.state(), MessageState.RECEIVED, "INGESTED",
                synthesized ? "received on inbound (correlation id synthesized — header absent)"
                        : "received on inbound failure topic");

        // Forward to async classification, preserving original headers and opaque payload.
        publisher.send(new ProducerRecord<>(topics.classify(), null, correlationId,
                record.value(), record.headers()));

        log.debug("Ingested failure {} (attempt {}) from {}", correlationId, rec.attemptCount(),
                rec.originalTopic());
    }

    private static Integer intOrNull(Headers h, String key) {
        String v = FailureHeaders.getString(h, key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long longOrNull(Headers h, String key) {
        String v = FailureHeaders.getString(h, key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Map<String, String> headerMap(Headers headers) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Header header : headers) {
            if (header.value() != null) {
                map.put(header.key(), new String(header.value(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return map;
    }
}
