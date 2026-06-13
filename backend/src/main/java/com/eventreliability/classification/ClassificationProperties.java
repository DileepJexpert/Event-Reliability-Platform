package com.eventreliability.classification;

import java.util.List;

import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.RecommendedAction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The externalised, config-driven classification rule table (§11). Loaded from
 * {@code classification-rules.yml} so operators can extend or reorder rules without a redeploy.
 * Rules are evaluated in order; the first whose patterns match wins.
 */
@ConfigurationProperties(prefix = "reliability.classification")
public record ClassificationProperties(List<Rule> rules) {

    public ClassificationProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * One rule. A null/blank pattern means "don't constrain on this field"; a rule with neither
     * pattern set is a catch-all. Patterns are case-insensitive regular expressions matched against
     * the whole value (use {@code .*…​.*} for substring matching).
     *
     * @param name             label used in the audit trail
     * @param exceptionPattern regex matched against {@code x-exception-class}
     * @param messagePattern   regex matched against {@code x-exception-message}
     * @param classification   resulting taxonomy class
     * @param action           recommended action
     * @param reason           human-readable rationale recorded on the failure
     */
    public record Rule(
            String name,
            String exceptionPattern,
            String messagePattern,
            FailureClassification classification,
            RecommendedAction action,
            String reason) {
    }
}
