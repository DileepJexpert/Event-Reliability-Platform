package com.eventreliability.classification;

import java.time.Duration;
import java.util.List;

import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.RecommendedAction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The externalised, config-driven classification rule table (§11). Loaded from
 * {@code classification-rules.yml} so operators can extend or reorder rules without a redeploy.
 * Rules are evaluated in order; the first whose patterns match wins. An optional self-hosted LLM
 * (Ollama) can be enabled as a fallback for ambiguous cases — the §11 future hook.
 */
@ConfigurationProperties(prefix = "reliability.classification")
public record ClassificationProperties(List<Rule> rules, @DefaultValue Llm llm) {

    public ClassificationProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
        if (llm == null) {
            llm = new Llm(false, "http://localhost:11434", "llama3.1", Duration.ofSeconds(20), "unknown-only");
        }
    }

    /**
     * Optional LLM-assisted classification via a self-hosted Ollama endpoint (§11). Disabled by
     * default; runs on the async classification path so it never throttles ingestion.
     *
     * @param enabled whether to consult the LLM at all
     * @param baseUrl Ollama base URL (laptop default {@code http://localhost:11434}; at the bank, an
     *                internal endpoint)
     * @param model   model name to run (e.g. {@code llama3.1})
     * @param timeout per-request connect/read timeout
     * @param mode    {@code unknown-only} (consult the LLM only when rules yield UNKNOWN — default) or
     *                {@code always} (consult for every message)
     */
    public record Llm(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("llama3.1") String model,
            @DefaultValue("PT20S") Duration timeout,
            @DefaultValue("unknown-only") String mode) {

        public boolean always() {
            return "always".equalsIgnoreCase(mode);
        }
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
