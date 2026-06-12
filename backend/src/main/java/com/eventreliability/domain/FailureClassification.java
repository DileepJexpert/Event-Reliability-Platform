package com.eventreliability.domain;

/**
 * Failure taxonomy (§6.1). The classification drives which lane a message is routed into (§7.1).
 */
public enum FailureClassification {

    /** Downstream blip / timeout; will likely succeed on retry. */
    TRANSIENT(true),

    /** Connectivity / broker / resource issue; retry after backoff. */
    INFRASTRUCTURE(true),

    /** Valid business rejection (account frozen, limit exceeded…); retrying never helps. */
    BUSINESS(false),

    /** Genuinely malformed; will crash the consumer every time. Quarantine immediately. */
    POISON(false),

    /** Default when no rule matches; treat conservatively — park for human review, never blind-retry. */
    UNKNOWN(false);

    private final boolean retryable;

    FailureClassification(boolean retryable) {
        this.retryable = retryable;
    }

    /** Whether messages of this class are eligible for the automatic tiered-retry lane (§7.1). */
    public boolean isRetryable() {
        return retryable;
    }

    public static FailureClassification fromHeader(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
