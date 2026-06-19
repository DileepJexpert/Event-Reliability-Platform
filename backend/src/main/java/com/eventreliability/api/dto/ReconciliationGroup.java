package com.eventreliability.api.dto;

/** Completeness for one grouping (a source app or topic): completed vs open, with oldest-open age. */
public record ReconciliationGroup(
        String name,
        long total,
        long completed,
        long open,
        double completionRate,
        Long oldestOpenAt) {
}
