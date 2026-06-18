package com.eventreliability.api.dto;

import java.util.List;

/**
 * Adaptive anomaly detection result (§16 — {@code GET /api/anomalies}). Each series (root-cause
 * signature, source topic) is compared against its own recent baseline; series whose latest-bucket
 * count exceeds {@code mean + sensitivity·stddev} are returned, highest score first.
 *
 * @param anomalies      flagged series, ranked by score (most anomalous first)
 * @param bucketMillis   the counting bucket size used
 * @param lookbackMillis the baseline window used
 * @param sensitivity    std-devs above mean that counted as anomalous
 * @param generatedAt    epoch millis the analysis ran
 */
public record AnomalyDto(
        List<AnomalyItem> anomalies,
        long bucketMillis,
        long lookbackMillis,
        double sensitivity,
        long generatedAt) {
}
