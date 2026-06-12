package com.eventreliability.classification;

import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.RecommendedAction;

/**
 * Outcome of classifying a failure: the taxonomy class, the recommended action, a human-readable
 * reason and the name of the rule that matched (for the audit trail).
 */
public record ClassificationResult(
        FailureClassification classification,
        RecommendedAction action,
        String reason,
        String matchedRule) {
}
