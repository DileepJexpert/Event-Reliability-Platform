package com.eventreliability.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A declared reconciliation expectation: a producer (e.g. an EOD batch) states "I expect {@code
 * expectedCount} events for {@code source} (optionally within a time window) to be processed." Brod
 * reconciles that declared total against the failures it captured for that source, reporting how many
 * are still un-recovered (the shortfall). Stored on the compacted {@code reliability.views.expectations}
 * topic, keyed by {@link #key}, so the latest declaration per key survives.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Expectation(
        String key,
        String source,
        long expectedCount,
        String label,
        Long windowStartMs,
        Long windowEndMs,
        long declaredAt,
        String declaredBy) {
}
