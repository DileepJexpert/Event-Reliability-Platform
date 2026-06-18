package com.eventreliability.api.dto;

/**
 * One anomalous series.
 *
 * @param dimension          {@code ROOT_CAUSE} or {@code TOPIC}
 * @param key                the signature / topic that spiked
 * @param recentCount        count in the latest bucket
 * @param baselineMean       mean per-bucket count over the baseline window
 * @param expected           the adaptive threshold crossed ({@code mean + sensitivity·stddev})
 * @param score              how many std-devs (roughly) above the mean the latest bucket sits
 * @param sampleCorrelationId one correlation id from the spike, for drill-in
 */
public record AnomalyItem(
        String dimension,
        String key,
        long recentCount,
        double baselineMean,
        double expected,
        double score,
        String sampleCorrelationId) {
}
