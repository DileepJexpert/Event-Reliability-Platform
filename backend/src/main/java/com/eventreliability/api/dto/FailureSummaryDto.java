package com.eventreliability.api.dto;

import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.MessageState;
import com.eventreliability.domain.RecommendedAction;

/** Compact failure projection for the failures list (§16 — failures list screen). */
public record FailureSummaryDto(
        String correlationId,
        MessageState state,
        FailureClassification classification,
        RecommendedAction recommendedAction,
        String originalTopic,
        String sourceApp,
        String exceptionClass,
        String exceptionMessage,
        int attemptCount,
        String currentTier,
        String rootCauseSignature,
        String reason,
        Long firstFailedAt,
        Long updatedAt) {
}
