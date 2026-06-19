package com.brod.starter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

/**
 * When a consumer exhausts its retries, Spring Kafka's default recoverer logs-and-skips — silently
 * dropping the message. This recoverer instead republishes the failed record to Brod's inbound DLQ with
 * the {@link BrodHeaders} contract stamped, turning "retry then drop" into "retry then preserve". Brod
 * then classifies, retries and surfaces it — so no failed (financial) event is ever lost without a trace.
 */
public class BrodDeadLetterRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(BrodDeadLetterRecoverer.class);
    private static final int MAX_STACKTRACE = 8000;

    private final KafkaTemplate<Object, Object> template;
    private final String dlqTopic;
    private final String sourceApp;

    public BrodDeadLetterRecoverer(KafkaTemplate<Object, Object> template, String dlqTopic, String sourceApp) {
        this.template = template;
        this.dlqTopic = dlqTopic;
        this.sourceApp = sourceApp;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        Throwable cause = businessCause(exception);
        Headers h = new RecordHeaders();
        for (Header existing : record.headers()) {
            h.add(existing.key(), existing.value());
        }
        put(h, BrodHeaders.ORIGINAL_TOPIC, record.topic());
        put(h, BrodHeaders.ORIGINAL_PARTITION, Integer.toString(record.partition()));
        put(h, BrodHeaders.ORIGINAL_OFFSET, Long.toString(record.offset()));
        if (record.key() != null) {
            put(h, BrodHeaders.ORIGINAL_KEY, record.key().toString());
        }
        put(h, BrodHeaders.EXCEPTION_CLASS, cause.getClass().getName());
        put(h, BrodHeaders.EXCEPTION_MESSAGE, cause.getMessage());
        put(h, BrodHeaders.STACKTRACE, stackTrace(cause));
        if (sourceApp != null && !sourceApp.isBlank()) {
            put(h, BrodHeaders.SOURCE_APP, sourceApp);
        }
        putIfAbsent(h, BrodHeaders.ATTEMPT_COUNT, "1");
        putIfAbsent(h, BrodHeaders.FIRST_FAILED_AT, Long.toString(System.currentTimeMillis()));
        putIfAbsent(h, BrodHeaders.CORRELATION_ID, correlationId(record));

        template.send(new ProducerRecord<>(dlqTopic, null, record.key(), record.value(), h));
        log.warn("Captured exhausted-retry record {}-{}@{} to Brod DLQ '{}' ({}: {})",
                record.topic(), record.partition(), record.offset(), dlqTopic,
                cause.getClass().getSimpleName(), cause.getMessage());
    }

    /** Unwrap Spring's listener wrapper to the exception the business handler actually threw. */
    private static Throwable businessCause(Exception exception) {
        if (exception instanceof ListenerExecutionFailedException && exception.getCause() != null) {
            return exception.getCause();
        }
        return exception;
    }

    private static String correlationId(ConsumerRecord<?, ?> record) {
        Header existing = record.headers().lastHeader(BrodHeaders.CORRELATION_ID);
        if (existing != null && existing.value() != null) {
            return new String(existing.value(), StandardCharsets.UTF_8);
        }
        return record.key() != null ? record.key().toString() : "gen-" + UUID.randomUUID();
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > MAX_STACKTRACE ? s.substring(0, MAX_STACKTRACE) : s;
    }

    private static void put(Headers headers, String key, String value) {
        if (value == null) {
            return;
        }
        headers.remove(key);
        headers.add(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void putIfAbsent(Headers headers, String key, String value) {
        if (headers.lastHeader(key) == null) {
            headers.add(key, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
