package com.example.dlqsample;

/**
 * The failure-header contract the Event Reliability Platform consumes (§6.3) — the ONLY thing an
 * owning application has to adopt. When your consumer cannot process a message, republish it to the
 * platform's DLQ topic ({@code <prefix>failures.inbound}) carrying these headers. The platform reasons
 * over these headers and treats the payload body as opaque bytes.
 *
 * <p>Copied verbatim from the platform so this sample stands alone.
 */
public final class FailureHeaders {

    private FailureHeaders() {
    }

    /** REQUIRED. Stable id for the failing message; the platform keys all of its state on this. */
    public static final String CORRELATION_ID = "x-correlation-id";
    /** The topic the message was originally consumed from when processing failed (used for replay). */
    public static final String ORIGINAL_TOPIC = "x-original-topic";
    public static final String ORIGINAL_PARTITION = "x-original-partition";
    public static final String ORIGINAL_OFFSET = "x-original-offset";
    /** Drives classification, e.g. a {@code *Timeout*} class → TRANSIENT → AUTO_RETRY. */
    public static final String EXCEPTION_CLASS = "x-exception-class";
    /** Drives classification and is shown in the console. */
    public static final String EXCEPTION_MESSAGE = "x-exception-message";
    public static final String STACKTRACE = "x-stacktrace";
    /** Defaults to 1 (the original processing attempt) if absent. */
    public static final String ATTEMPT_COUNT = "x-attempt-count";
    /** Epoch millis of the first failure; defaults to ingest time if absent. */
    public static final String FIRST_FAILED_AT = "x-first-failed-at";
    /** Owning application name. */
    public static final String SOURCE_APP = "x-source-app";
    /** Optional; part of the root-cause signature used for pattern detection / schema-drift inference. */
    public static final String SCHEMA_VERSION = "x-schema-version";
}
