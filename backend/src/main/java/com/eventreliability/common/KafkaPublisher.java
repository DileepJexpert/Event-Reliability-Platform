package com.eventreliability.common;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Centralised producer for every platform topic. Wraps the auto-configured, idempotent {@code
 * KafkaTemplate<String, byte[]>} (§18.4) so callers don't repeat serialization/header plumbing.
 * Keys are always the correlation id (or a root-cause signature for the detection stream).
 *
 * <p>Every outbound publish is logged at INFO ({@code SEND -> topic=… key=…}) so the whole message
 * flow is traceable end to end. Quiet it with {@code logging.level.com.eventreliability.common.KafkaPublisher=WARN}.
 */
@Component
public class KafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPublisher.class);

    private final KafkaTemplate<String, byte[]> template;
    private final JsonCodec json;

    public KafkaPublisher(KafkaTemplate<String, byte[]> template, JsonCodec json) {
        this.template = template;
        this.json = json;
    }

    /** Send opaque bytes (e.g. an original business payload being re-driven). */
    public CompletableFuture<SendResult<String, byte[]>> send(String topic, String key, byte[] value) {
        log.info("SEND -> topic={} key={} bytes={}", topic, key, value == null ? 0 : value.length);
        return template.send(topic, key, value);
    }

    /** Send a fully-formed record, e.g. to preserve original headers when re-driving a payload. */
    public CompletableFuture<SendResult<String, byte[]>> send(ProducerRecord<String, byte[]> record) {
        log.info("SEND -> topic={} key={} bytes={} headers={}", record.topic(), record.key(),
                record.value() == null ? 0 : record.value().length, headerKeys(record));
        return template.send(record);
    }

    /** Serialise a platform control/state record to JSON bytes and send it. */
    public CompletableFuture<SendResult<String, byte[]>> sendJson(String topic, String key, Object value) {
        byte[] bytes = json.toBytes(value);
        log.info("SEND -> topic={} key={} bytes={} type={}", topic, key, bytes == null ? 0 : bytes.length,
                value == null ? "null" : value.getClass().getSimpleName());
        return template.send(topic, key, bytes);
    }

    /** Write a tombstone (null value) — used to forget terminal-and-aged compacted state (§9). */
    public CompletableFuture<SendResult<String, byte[]>> tombstone(String topic, String key) {
        log.info("SEND -> topic={} key={} (tombstone)", topic, key);
        return template.send(topic, key, null);
    }

    /** Compact "[k1,k2,…]" of the record's header keys, for the flow log. */
    private static String headerKeys(ProducerRecord<String, byte[]> record) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Header header : record.headers()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(header.key());
            first = false;
        }
        return sb.append(']').toString();
    }
}
