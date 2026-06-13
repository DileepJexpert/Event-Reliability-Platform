package com.example.dlqsample;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link SampleFailure} templates onto the platform's DLQ topic with the full failure-header
 * contract — exactly as a real owning application would when it gives up on a message.
 */
@Component
public class DlqFailureProducer {

    private static final Logger log = LoggerFactory.getLogger(DlqFailureProducer.class);

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final String sourceApp;
    /** Synthetic, monotonically increasing original-offset, just to make the samples look realistic. */
    private final AtomicLong offsetSeq = new AtomicLong(1_000);

    public DlqFailureProducer(KafkaTemplate<String, String> kafka,
                              @Value("${dlq.topic}") String topic,
                              @Value("${dlq.source-app}") String sourceApp) {
        this.kafka = kafka;
        this.topic = topic;
        this.sourceApp = sourceApp;
    }

    public String topic() {
        return topic;
    }

    /** Send one failure; blocks briefly so a missing-topic / broker-down error surfaces clearly. */
    public String send(SampleFailure f) {
        String correlationId = UUID.randomUUID().toString();
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, null, correlationId, f.payloadJson());
        stampHeaders(record.headers(), correlationId, f);
        try {
            kafka.send(record).get(12, TimeUnit.SECONDS);
        } catch (Exception ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(friendlyHint(ex), ex);
        }
        log.info("-> '{}'  [{}]  corr={}  ex={}  ({})",
                topic, f.label(), correlationId, f.exceptionClass(), f.expectedOutcome());
        return correlationId;
    }

    /**
     * Send {@code count} failures that all share one root-cause signature (same exception + topic +
     * schema-version) to trip the platform's windowed pattern detector and raise an incident.
     * Fire-and-forget plus a single flush, so it stays fast for hundreds of messages.
     */
    public int sendStorm(SampleFailure template, int count) {
        for (int i = 0; i < count; i++) {
            String correlationId = UUID.randomUUID().toString();
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, null, correlationId, template.payloadJson());
            stampHeaders(record.headers(), correlationId, template);
            kafka.send(record);
        }
        kafka.flush();
        log.info("-> '{}'  storm of {} x [{}]  (shared root cause -> expect an incident)",
                topic, count, template.label());
        return count;
    }

    private void stampHeaders(Headers h, String correlationId, SampleFailure f) {
        put(h, FailureHeaders.CORRELATION_ID, correlationId);
        put(h, FailureHeaders.ORIGINAL_TOPIC, f.originalTopic());
        put(h, FailureHeaders.ORIGINAL_PARTITION, "0");
        put(h, FailureHeaders.ORIGINAL_OFFSET, Long.toString(offsetSeq.getAndIncrement()));
        put(h, FailureHeaders.EXCEPTION_CLASS, f.exceptionClass());
        put(h, FailureHeaders.EXCEPTION_MESSAGE, f.exceptionMessage());
        put(h, FailureHeaders.STACKTRACE, syntheticStacktrace(f));
        put(h, FailureHeaders.ATTEMPT_COUNT, "1");
        put(h, FailureHeaders.FIRST_FAILED_AT, Long.toString(System.currentTimeMillis()));
        put(h, FailureHeaders.SOURCE_APP, sourceApp);
        put(h, FailureHeaders.SCHEMA_VERSION, "v1");
    }

    private static String syntheticStacktrace(SampleFailure f) {
        String pkg = sourcePackage(f.exceptionClass());
        return f.exceptionClass() + ": " + f.exceptionMessage()
                + "\n\tat " + pkg + ".Consumer.handle(Consumer.java:42)"
                + "\n\tat " + pkg + ".Consumer.onMessage(Consumer.java:27)";
    }

    private static String sourcePackage(String exceptionClass) {
        int dot = exceptionClass.lastIndexOf('.');
        return dot > 0 ? exceptionClass.substring(0, dot) : "org.example";
    }

    private static void put(Headers headers, String key, String value) {
        headers.add(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private String friendlyHint(Exception ex) {
        return "Failed to publish to '" + topic + "'. Is the platform running? The broker has"
                + " auto-create disabled, so this topic only exists once the backend has provisioned"
                + " it at startup. Start: (1) docker compose up -d  (2) the backend (mvn spring-boot:run"
                + " in backend/). Underlying cause: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }
}
