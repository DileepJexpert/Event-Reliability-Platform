package com.eventreliability.api.dto;

import java.util.List;
import java.util.Map;

import com.eventreliability.domain.AuditEvent;
import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.MessageState;
import com.eventreliability.domain.RecommendedAction;

/**
 * Full failure detail including the complete audit timeline (§15 — {@code GET /api/failures/{id}}).
 * The opaque payload is returned base64-encoded for operator inspection; access is gated to
 * authenticated, authorised users (§17).
 */
public record FailureDetailDto(
        String correlationId,
        MessageState state,
        FailureClassification classification,
        RecommendedAction recommendedAction,
        String originalTopic,
        String dlqTopic,
        Integer originalPartition,
        Long originalOffset,
        String sourceApp,
        String exceptionClass,
        String exceptionMessage,
        String stacktrace,
        int attemptCount,
        String currentTier,
        Long eligibleAt,
        String schemaVersion,
        String payloadHash,
        String rootCauseSignature,
        String reason,
        String lastError,
        String lastActor,
        String payloadBase64,
        Map<String, String> headers,
        Long firstFailedAt,
        Long createdAt,
        Long updatedAt,
        List<AuditEvent> auditTimeline) {
}
