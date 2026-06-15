package com.eventreliability.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.RootCauseSignature;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.stereotype.Component;

/**
 * Maps a failure message (correlation-id key + headers + opaque payload) onto a {@link
 * FailureRecord.Builder}, deriving the root-cause signature. Shared by ingestion and classification
 * so the header contract (§6.3) is interpreted in exactly one place; the caller sets the lifecycle
 * state and any classification fields.
 */
@Component
public class FailureRecordFactory {

    public FailureRecord.Builder fromMessage(String correlationId, Headers h, byte[] payload) {
        long now = System.currentTimeMillis();
        FailureRecord.Builder b = FailureRecord.builder()
                .correlationId(correlationId)
                .originalTopic(FailureHeaders.getString(h, FailureHeaders.ORIGINAL_TOPIC))
                .dlqTopic(FailureHeaders.getString(h, FailureHeaders.DLQ_TOPIC))
                .originalPartition(intOrNull(h, FailureHeaders.ORIGINAL_PARTITION))
                .originalOffset(longOrNull(h, FailureHeaders.ORIGINAL_OFFSET))
                .exceptionClass(FailureHeaders.getString(h, FailureHeaders.EXCEPTION_CLASS))
                .exceptionMessage(FailureHeaders.getString(h, FailureHeaders.EXCEPTION_MESSAGE))
                .stacktrace(FailureHeaders.getString(h, FailureHeaders.STACKTRACE))
                // attempt count defaults to 1 (the original processing attempt that failed).
                .attemptCount(FailureHeaders.getInt(h, FailureHeaders.ATTEMPT_COUNT, 1))
                .firstFailedAt(FailureHeaders.getLong(h, FailureHeaders.FIRST_FAILED_AT, now))
                .sourceApp(FailureHeaders.getString(h, FailureHeaders.SOURCE_APP))
                .schemaVersion(FailureHeaders.getString(h, FailureHeaders.SCHEMA_VERSION))
                .payloadHash(FailureHeaders.getString(h, FailureHeaders.PAYLOAD_HASH))
                .payloadBase64(payload == null ? null : Base64.getEncoder().encodeToString(payload))
                .headers(headerMap(h));
        b.rootCauseSignature(RootCauseSignature.of(
                FailureHeaders.getString(h, FailureHeaders.EXCEPTION_CLASS),
                FailureHeaders.getString(h, FailureHeaders.ORIGINAL_TOPIC),
                FailureHeaders.getString(h, FailureHeaders.SCHEMA_VERSION)));
        return b;
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
                map.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
            }
        }
        return map;
    }
}
