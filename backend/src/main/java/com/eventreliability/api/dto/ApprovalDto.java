package com.eventreliability.api.dto;

/**
 * A maker-checker request as shown to the checker (§13). Carries both the original payload and the
 * maker's corrected payload so the console can show a diff before the checker approves.
 */
public record ApprovalDto(
        String requestId,
        String type,
        String correlationId,
        String incidentId,
        String maker,
        String makerReason,
        String targetTopic,
        boolean payloadEdited,
        String payloadOverrideBase64,
        String originalPayloadBase64,
        String exceptionClass,
        String originalTopic,
        String status,
        long createdAt,
        String checker,
        String checkerReason,
        Long decidedAt) {
}
