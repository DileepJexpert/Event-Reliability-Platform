package com.eventreliability.classification;

import java.util.Optional;

/**
 * Optional LLM-assisted classifier (§11). Returns a verdict for an ambiguous failure, or empty if it
 * is disabled, unavailable, or not confident. Implementations must be resilient — a failure here must
 * never break the classification pipeline; callers fall back to the conservative rule result.
 */
public interface LlmClassifier {

    Optional<ClassificationResult> classify(String exceptionClass, String exceptionMessage);
}
