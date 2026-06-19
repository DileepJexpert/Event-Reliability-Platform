package com.eventreliability.api.dto;

/**
 * One row of the compliance export (§17): a failed transaction and its current disposition, for a
 * regulator-ready register. Aggregated/metadata only — no payload bytes.
 */
public record ComplianceRow(
        String correlationId,
        String firstFailedAt,
        String state,
        String classification,
        String owningTeam,
        String originalTopic,
        String dlqTopic,
        String sourceApp,
        String exceptionClass,
        String exceptionMessage,
        int attemptCount,
        String currentTier,
        String lastActor,
        String reason,
        String updatedAt) {
}
