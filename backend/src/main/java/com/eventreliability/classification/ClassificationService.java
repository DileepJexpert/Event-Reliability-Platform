package com.eventreliability.classification;

import com.eventreliability.domain.FailureClassification;

import org.springframework.stereotype.Service;

/**
 * Composes the deterministic rule table with the optional LLM fallback (§11). The rule table runs
 * first (fast, auditable); the LLM is consulted only when configured — for ambiguous {@code UNKNOWN}
 * results by default, or for every message in {@code always} mode — and only its confident,
 * non-UNKNOWN verdicts override the rule result. If the LLM is disabled or unavailable, behaviour is
 * exactly the rule-based classifier, so existing flows and tests are unaffected.
 */
@Service
public class ClassificationService {

    private final Classifier ruleClassifier;
    private final LlmClassifier llmClassifier;
    private final ClassificationProperties.Llm llmConfig;

    public ClassificationService(Classifier ruleClassifier, LlmClassifier llmClassifier,
                                 ClassificationProperties properties) {
        this.ruleClassifier = ruleClassifier;
        this.llmClassifier = llmClassifier;
        this.llmConfig = properties.llm();
    }

    public ClassificationResult classify(String exceptionClass, String exceptionMessage) {
        ClassificationResult ruleResult = ruleClassifier.classify(exceptionClass, exceptionMessage);
        if (!llmConfig.enabled()) {
            return ruleResult;
        }
        boolean consultLlm = llmConfig.always() || ruleResult.classification() == FailureClassification.UNKNOWN;
        if (!consultLlm) {
            return ruleResult;
        }
        return llmClassifier.classify(exceptionClass, exceptionMessage).orElse(ruleResult);
    }
}
