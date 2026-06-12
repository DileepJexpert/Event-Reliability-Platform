package com.eventreliability.common;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Centralised producer for every platform topic. Wraps the auto-configured, idempotent {@code
 * KafkaTemplate<String, byte[]>} (§18.4) so callers don't repeat serialization/header plumbing.
 * Keys are always the correlation id (or a root-cause signature for the detection stream).
 */
@Component
public class KafkaPublisher {

    private final KafkaTemplate<String, byte[]> template;
    private final JsonCodec json;

    public KafkaPublisher(KafkaTemplate<String, byte[]> template, JsonCodec json) {
        this.template = template;
        this.json = json;
    }

    /** Send opaque bytes (e.g. an original business payload being re-driven). */
    public CompletableFuture<SendResult<String, byte[]>> send(String topic, String key, byte[] value) {
        return template.send(topic, key, value);
    }

    /** Send a fully-formed record, e.g. to preserve original headers when re-driving a payload. */
    public CompletableFuture<SendResult<String, byte[]>> send(ProducerRecord<String, byte[]> record) {
        return template.send(record);
    }

    /** Serialise a platform control/state record to JSON bytes and send it. */
    public CompletableFuture<SendResult<String, byte[]>> sendJson(String topic, String key, Object value) {
        return template.send(topic, key, json.toBytes(value));
    }

    /** Write a tombstone (null value) — used to forget terminal-and-aged compacted state (§9). */
    public CompletableFuture<SendResult<String, byte[]>> tombstone(String topic, String key) {
        return template.send(topic, key, null);
    }
}
