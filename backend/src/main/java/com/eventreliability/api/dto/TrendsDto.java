package com.eventreliability.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Aggregated trend / analytics view (§16), computed on demand from the current state read model — no
 * DB and no extra topology. Powers the console's Trends tab: headline counts, resolution rate and
 * MTTR, breakdowns by classification and state, the top failing source topics and apps, and a daily
 * failure count for the last two weeks.
 */
public record TrendsDto(
        long total,
        long open,
        /** Recovered: a retry succeeded (RESOLVED) or the message was re-driven (REPLAYED). */
        long resolved,
        long parked,
        double resolutionRate,
        /** Mean time to resolution (ms) over recovered failures; null when none have resolved. */
        Long mttrMillis,
        Map<String, Long> byClassification,
        Map<String, Long> byState,
        List<NameCount> topTopics,
        List<NameCount> topSourceApps,
        List<DailyCount> daily) {

    /** A name (source topic / app) with its failure count. */
    public record NameCount(String name, long count) {
    }

    /** Failures first-seen on a given UTC date ({@code yyyy-MM-dd}). */
    public record DailyCount(String date, long count) {
    }
}
