package com.eventreliability.api.dto;

/**
 * Declare a reconciliation expectation ({@code POST /api/reconciliation/expectations}). {@code key} is a
 * unique id for the batch/window; {@code source} is the topic its events belong to; {@code expectedCount}
 * is how many the producer expects processed. The optional window scopes which captured failures count.
 */
public record ExpectationRequest(
        String key,
        String source,
        long expectedCount,
        String label,
        Long windowStartMs,
        Long windowEndMs) {
}
