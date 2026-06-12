package com.eventreliability.domain;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * The failure-header contract (§6.3) — the only thing owning apps must adopt. When a business
 * consumer cannot process a message it republishes it to {@link com.eventreliability.config.TopicNames#inbound()}
 * carrying these headers. The platform reasons over these headers, never over the deserialized
 * payload body (which is treated as opaque bytes).
 */
public final class FailureHeaders {

    private FailureHeaders() {
    }

    /** Used as the state key. */
    public static final String CORRELATION_ID = "x-correlation-id";
    public static final String ORIGINAL_TOPIC = "x-original-topic";
    public static final String ORIGINAL_PARTITION = "x-original-partition";
    public static final String ORIGINAL_OFFSET = "x-original-offset";
    public static final String EXCEPTION_CLASS = "x-exception-class";
    public static final String EXCEPTION_MESSAGE = "x-exception-message";
    public static final String STACKTRACE = "x-stacktrace";
    public static final String ATTEMPT_COUNT = "x-attempt-count";
    public static final String FIRST_FAILED_AT = "x-first-failed-at";
    public static final String SOURCE_APP = "x-source-app";

    /** Set by the platform during scheduling (§10) — epoch millis at which a retry becomes eligible. */
    public static final String ELIGIBLE_AT = "x-eligible-at";

    /** Set by the platform after classification (§7.1). */
    public static final String CLASSIFICATION = "x-classification";

    /** Optional; used for schema-drift inference since there is no Schema Registry (§12). */
    public static final String SCHEMA_VERSION = "x-schema-version";
    public static final String PAYLOAD_HASH = "x-payload-hash";

    /** Internal: which retry tier a scheduled message belongs to. */
    public static final String RETRY_TIER = "x-retry-tier";

    /**
     * Optional: the original Kafka message key, so a re-drive can preserve source-topic partitioning
     * and ordering. The base header contract does not carry the key; onboarded apps that need
     * ordering on re-drive may set this.
     */
    public static final String ORIGINAL_KEY = "x-original-key";

    // ----- read helpers -----

    public static String getString(Headers headers, String key) {
        if (headers == null) {
            return null;
        }
        Header h = headers.lastHeader(key);
        if (h == null || h.value() == null) {
            return null;
        }
        return new String(h.value(), StandardCharsets.UTF_8);
    }

    public static long getLong(Headers headers, String key, long defaultValue) {
        String v = getString(headers, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public static int getInt(Headers headers, String key, int defaultValue) {
        String v = getString(headers, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    // ----- write helpers -----

    public static void put(Headers headers, String key, String value) {
        if (value == null) {
            return;
        }
        headers.remove(key);
        headers.add(key, value.getBytes(StandardCharsets.UTF_8));
    }

    public static void put(Headers headers, String key, long value) {
        put(headers, key, Long.toString(value));
    }

    /** A mutable copy of the headers, so the platform can stamp its own headers without mutating the source. */
    public static Headers copy(Headers src) {
        RecordHeaders out = new RecordHeaders();
        if (src != null) {
            for (Header h : src) {
                out.add(h.key(), h.value());
            }
        }
        return out;
    }
}
