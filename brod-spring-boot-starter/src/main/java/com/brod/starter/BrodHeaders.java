package com.brod.starter;

/**
 * The Event Reliability Platform failure-header contract — the only coupling between an owning app and
 * Brod. These names must match the platform's {@code FailureHeaders} exactly; the starter stamps them
 * onto records it captures so Brod can classify, route and retry them. (Deliberately duplicated here so
 * the starter has no dependency on the backend jar.)
 */
public final class BrodHeaders {

    private BrodHeaders() {
    }

    public static final String CORRELATION_ID = "x-correlation-id";
    public static final String ORIGINAL_TOPIC = "x-original-topic";
    public static final String ORIGINAL_PARTITION = "x-original-partition";
    public static final String ORIGINAL_OFFSET = "x-original-offset";
    public static final String ORIGINAL_KEY = "x-original-key";
    public static final String EXCEPTION_CLASS = "x-exception-class";
    public static final String EXCEPTION_MESSAGE = "x-exception-message";
    public static final String STACKTRACE = "x-stacktrace";
    public static final String ATTEMPT_COUNT = "x-attempt-count";
    public static final String FIRST_FAILED_AT = "x-first-failed-at";
    public static final String SOURCE_APP = "x-source-app";
}
