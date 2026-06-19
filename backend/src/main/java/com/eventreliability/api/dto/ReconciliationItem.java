package com.eventreliability.api.dto;

/** A single open (un-reconciled) failure, for the "oldest unreconciled" worklist. */
public record ReconciliationItem(
        String correlationId,
        String topic,
        String team,
        String state,
        Long firstFailedAt) {
}
