package com.eventreliability.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.eventreliability.api.dto.FailureIntakeRequest;
import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * HTTP intake for the "all-error handler". A non-Kafka producer (batch/EOD job, REST integration,
 * file processor) submits a failure over HTTP; this service builds the {@link FailureHeaders} contract
 * (§6.3) from the request and publishes it to the inbound DLQ topic. The submitted failure then flows
 * through exactly the same ingestion -> classification -> retry/park pipeline as a Kafka DLQ failure
 * and appears on the console identically. The opaque payload is preserved for triage/replay.
 */
@Service
public class FailureIntakeService {

    private static final Logger log = LoggerFactory.getLogger(FailureIntakeService.class);

    private final KafkaPublisher publisher;
    private final TopicNames topics;

    public FailureIntakeService(KafkaPublisher publisher, TopicNames topics) {
        this.publisher = publisher;
        this.topics = topics;
    }

    /**
     * Validate, stamp the header contract, publish to the inbound DLQ, and return the correlation id
     * (the caller's, or a generated {@code http-…} one). Bad input throws {@link IllegalArgumentException}
     * (translated to 400 by the global handler).
     */
    public String submit(FailureIntakeRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String source = trimToNull(req.source());
        String exceptionClass = trimToNull(req.exceptionClass());
        if (source == null) {
            throw new IllegalArgumentException("source is required (the logical origin: topic, job, endpoint, …)");
        }
        if (exceptionClass == null) {
            throw new IllegalArgumentException("exceptionClass is required");
        }
        String correlationId = trimToNull(req.correlationId());
        if (correlationId == null) {
            correlationId = "http-" + UUID.randomUUID();
        }

        RecordHeaders headers = new RecordHeaders();
        FailureHeaders.put(headers, FailureHeaders.CORRELATION_ID, correlationId);
        FailureHeaders.put(headers, FailureHeaders.ORIGINAL_TOPIC, source);
        FailureHeaders.put(headers, FailureHeaders.SOURCE_APP, trimToNull(req.sourceApp()));
        FailureHeaders.put(headers, FailureHeaders.EXCEPTION_CLASS, exceptionClass);
        FailureHeaders.put(headers, FailureHeaders.EXCEPTION_MESSAGE, trimToNull(req.exceptionMessage()));
        FailureHeaders.put(headers, FailureHeaders.STACKTRACE, trimToNull(req.stacktrace()));
        FailureHeaders.put(headers, FailureHeaders.SCHEMA_VERSION, trimToNull(req.schemaVersion()));

        byte[] payload = req.payload() == null ? null : req.payload().getBytes(StandardCharsets.UTF_8);
        publisher.send(new ProducerRecord<>(topics.inbound(), null, correlationId, payload, headers));
        log.info("HTTP intake accepted failure {} (source={}, app={})", correlationId, source, req.sourceApp());
        return correlationId;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
