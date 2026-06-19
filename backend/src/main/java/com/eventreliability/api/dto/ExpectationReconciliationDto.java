package com.eventreliability.api.dto;

/**
 * A declared expectation reconciled against the captured failures (§16).
 *
 * @param key            the expectation/batch id
 * @param source         the topic the events belong to
 * @param label          human description
 * @param expectedCount  declared number of events expected to be processed
 * @param failed         failures Brod captured matching this expectation
 * @param recovered      of those, how many were recovered (RESOLVED / REPLAYED)
 * @param open           captured failures still un-recovered (the known-incomplete)
 * @param completed      presumed completed = max(0, expected − open)
 * @param completionRate completed / expected, 0..1
 * @param status         {@code RECONCILED} (open == 0) or {@code SHORTFALL}
 * @param windowStartMs  optional window start used for matching
 * @param windowEndMs    optional window end used for matching
 * @param declaredAt     when the expectation was declared (epoch millis)
 * @param declaredBy     who declared it
 */
public record ExpectationReconciliationDto(
        String key,
        String source,
        String label,
        long expectedCount,
        long failed,
        long recovered,
        long open,
        long completed,
        double completionRate,
        String status,
        Long windowStartMs,
        Long windowEndMs,
        long declaredAt,
        String declaredBy) {
}
