package com.eventreliability.classification;

import java.util.Map;
import java.util.Optional;

import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.RecommendedAction;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * LLM classifier backed by a self-hosted <a href="https://ollama.com">Ollama</a> server (§11) — a
 * laptop for local testing, or an internal endpoint at the bank. Plain HTTP to {@code /api/generate}
 * with {@code format=json} so the model returns a strict JSON verdict; no external SaaS, consistent
 * with the platform's self-hosted ethos (§4).
 *
 * <p>Every failure mode (disabled, server down, timeout, bad JSON, unknown class) degrades to
 * {@link Optional#empty()} so the caller falls back to the conservative rule result — the LLM can
 * only ever <em>improve</em> a classification, never break the pipeline.
 */
@Component
public class OllamaClassifier implements LlmClassifier {

    private static final Logger log = LoggerFactory.getLogger(OllamaClassifier.class);

    private static final String PROMPT_TEMPLATE = """
            You are a failure classifier for a payments event-streaming platform. Classify the failure
            into exactly one of these categories:
            - TRANSIENT: a downstream blip or timeout; likely succeeds on retry.
            - INFRASTRUCTURE: connectivity, broker or resource issue; retry after backoff.
            - BUSINESS: a valid business rejection (e.g. account frozen, limit exceeded); retry never helps.
            - POISON: a malformed message that will crash the consumer every time.
            - UNKNOWN: cannot be determined from the information given.
            Respond ONLY with a JSON object: {"classification":"<ONE_OF_ABOVE>","reason":"<short reason>"}.

            Exception class: %s
            Exception message: %s
            """;

    private final ClassificationProperties.Llm config;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    public OllamaClassifier(ClassificationProperties properties, ObjectMapper mapper) {
        this.config = properties.llm();
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.timeout());
        factory.setReadTimeout(config.timeout());
        this.restClient = RestClient.builder().baseUrl(config.baseUrl()).requestFactory(factory).build();
        if (config.enabled()) {
            log.info("LLM classification enabled via Ollama at {} (model {})", config.baseUrl(), config.model());
        }
    }

    @Override
    public Optional<ClassificationResult> classify(String exceptionClass, String exceptionMessage) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> request = Map.of(
                    "model", config.model(),
                    "prompt", PROMPT_TEMPLATE.formatted(
                            exceptionClass == null ? "(none)" : exceptionClass,
                            exceptionMessage == null ? "(none)" : exceptionMessage),
                    "stream", false,
                    "format", "json",
                    "options", Map.of("temperature", 0));

            OllamaResponse response = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OllamaResponse.class);

            if (response == null || response.response() == null || response.response().isBlank()) {
                return Optional.empty();
            }

            Verdict verdict = mapper.readValue(response.response(), Verdict.class);
            FailureClassification classification = FailureClassification.fromHeader(verdict.classification());
            if (classification == FailureClassification.UNKNOWN) {
                return Optional.empty(); // no improvement over the rule result
            }
            String reason = (verdict.reason() == null || verdict.reason().isBlank())
                    ? "classified by LLM" : verdict.reason();
            return Optional.of(new ClassificationResult(
                    classification, RecommendedAction.defaultFor(classification), reason,
                    "llm:ollama/" + config.model()));
        } catch (Exception ex) {
            log.warn("Ollama classification unavailable ({}), falling back to rule result: {}",
                    config.baseUrl(), ex.toString());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OllamaResponse(String response, boolean done) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Verdict(String classification, String reason) {
    }
}
