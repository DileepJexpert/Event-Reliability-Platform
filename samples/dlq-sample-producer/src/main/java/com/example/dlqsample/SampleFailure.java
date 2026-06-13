package com.example.dlqsample;

/**
 * A template for a failure event. The producer assigns a fresh correlation id and a synthetic
 * partition/offset at send time; everything else here mirrors what a real owning app would publish.
 *
 * @param label            short id, also used by the REST endpoints (e.g. "transient")
 * @param originalTopic    topic the message failed on (becomes {@code x-original-topic})
 * @param exceptionClass   value of {@code x-exception-class} — what the classifier matches on
 * @param exceptionMessage value of {@code x-exception-message}
 * @param payloadJson      opaque message body (the platform never parses it)
 * @param expectedOutcome  human note describing what the platform should do with it
 */
public record SampleFailure(
        String label,
        String originalTopic,
        String exceptionClass,
        String exceptionMessage,
        String payloadJson,
        String expectedOutcome) {
}
