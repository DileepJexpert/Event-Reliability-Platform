package com.eventreliability.api.dto;

import java.util.List;

/**
 * Reconciliation / completeness (§16 — {@code GET /api/reconciliation}). Answers "has every failed
 * transaction reached completion?" over the captured backlog: a failure is <em>completed</em> when it
 * reached {@code RESOLVED} (a retry succeeded) or {@code REPLAYED} (re-driven for reprocessing); every
 * other state is still <em>open</em> (parked, quarantined, retrying, business-rejected, in-flight).
 * Read-only — aggregated counts only.
 *
 * @param totalCaptured  total failures examined
 * @param completed      failures that reached completion (RESOLVED / REPLAYED)
 * @param open           failures not yet complete (the reconciliation gap)
 * @param completionRate completed / total, 0..1
 * @param oldestOpenAt   first-failed timestamp of the oldest open item (epoch millis), or null
 * @param generatedAt    epoch millis the figure was computed
 * @param bySource       completeness grouped by source app
 * @param byTopic        completeness grouped by source topic
 * @param oldestOpen     the longest-unreconciled open items, for a worklist
 */
public record ReconciliationDto(
        long totalCaptured,
        long completed,
        long open,
        double completionRate,
        Long oldestOpenAt,
        long generatedAt,
        List<ReconciliationGroup> bySource,
        List<ReconciliationGroup> byTopic,
        List<ReconciliationItem> oldestOpen) {
}
