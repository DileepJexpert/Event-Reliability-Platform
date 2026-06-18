package com.eventreliability.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Financial exposure / "value at risk" (§ intelligence — {@code GET /api/exposure}). Aggregates the
 * business amount of stuck (un-recovered) failures. Only sums are returned — never raw payloads.
 *
 * @param atRiskByCurrency total stuck amount per currency (the headline KPI)
 * @param atRiskCount      number of stuck failures a numeric amount could be extracted from
 * @param withoutAmount    stuck failures with no parseable amount (payload not JSON / field absent)
 * @param totalConsidered  total stuck failures examined (= atRiskCount + withoutAmount)
 * @param byTeam           exposure grouped by owning team
 * @param byTopic          exposure grouped by source topic
 * @param topExposures     the single biggest stuck failures, for drill-in
 * @param oldestAtRiskAt   first-failed timestamp of the oldest stuck failure (epoch millis), or null
 * @param generatedAt      epoch millis the figure was computed
 */
public record ExposureDto(
        Map<String, Double> atRiskByCurrency,
        long atRiskCount,
        long withoutAmount,
        long totalConsidered,
        List<ExposureGroup> byTeam,
        List<ExposureGroup> byTopic,
        List<ExposureItem> topExposures,
        Long oldestAtRiskAt,
        long generatedAt) {
}
