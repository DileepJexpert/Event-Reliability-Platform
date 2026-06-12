package com.eventreliability.domain;

import org.apache.kafka.common.header.Headers;

/**
 * Derives a stable "root-cause signature" used both to group failures for pattern detection (§7.2)
 * and to label a {@link FailureRecord} for the console. Without a Schema Registry, drift is inferred
 * by symptom (§12): the signature folds together the exception type, the originating topic and the
 * optional schema-version hint, so a sudden burst of {@code Deser…@orders.v2} reads as one incident.
 */
public final class RootCauseSignature {

    private RootCauseSignature() {
    }

    public static String of(String exceptionClass, String originalTopic, String schemaVersion) {
        String ex = simpleName(exceptionClass);
        String topic = (originalTopic == null || originalTopic.isBlank()) ? "unknown-topic" : originalTopic;
        StringBuilder sb = new StringBuilder(ex).append('@').append(topic);
        if (schemaVersion != null && !schemaVersion.isBlank()) {
            sb.append("#v").append(schemaVersion);
        }
        return sb.toString();
    }

    public static String of(Headers headers) {
        return of(FailureHeaders.getString(headers, FailureHeaders.EXCEPTION_CLASS),
                FailureHeaders.getString(headers, FailureHeaders.ORIGINAL_TOPIC),
                FailureHeaders.getString(headers, FailureHeaders.SCHEMA_VERSION));
    }

    public static String of(FailureRecord record) {
        return of(record.exceptionClass(), record.originalTopic(), record.schemaVersion());
    }

    private static String simpleName(String exceptionClass) {
        if (exceptionClass == null || exceptionClass.isBlank()) {
            return "UnknownException";
        }
        int dot = exceptionClass.lastIndexOf('.');
        return dot >= 0 && dot < exceptionClass.length() - 1
                ? exceptionClass.substring(dot + 1)
                : exceptionClass;
    }
}
