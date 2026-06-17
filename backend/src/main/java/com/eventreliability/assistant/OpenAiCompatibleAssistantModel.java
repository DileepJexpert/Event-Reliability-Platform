package com.eventreliability.assistant;

import java.util.List;
import java.util.Map;

import com.eventreliability.common.JsonCodec;
import com.eventreliability.config.ReliabilityProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Default {@link AssistantModel}: talks to a self-hosted, OpenAI-compatible {@code /chat/completions}
 * endpoint (vLLM, TGI, Ollama, LocalAI or an internal LLM gateway). Because the endpoint is the bank's
 * own model, failed-event context never leaves the bank's network. Configure via
 * {@code reliability.assistant.*}; when no base URL is set the assistant reports itself unavailable.
 */
@Component
public class OpenAiCompatibleAssistantModel implements AssistantModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAssistantModel.class);

    private final ReliabilityProperties.Assistant cfg;
    private final ObjectMapper mapper;
    private final RestClient http;

    public OpenAiCompatibleAssistantModel(ReliabilityProperties props, JsonCodec json) {
        this.cfg = props.assistant();
        this.mapper = json.mapper();
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(5000);
        rf.setReadTimeout((int) cfg.timeout().toMillis());
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    public boolean available() {
        return cfg.enabled() && cfg.baseUrl() != null && !cfg.baseUrl().isBlank();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        if (!available()) {
            throw new IllegalStateException("assistant model not configured");
        }
        Map<String, Object> body = Map.of(
                "model", cfg.model(),
                "temperature", 0,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        try {
            String response = http.post()
                    .uri(endpoint())
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_JSON);
                        if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) {
                            h.setBearerAuth(cfg.apiKey());
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode content = mapper.readTree(response)
                    .path("choices").path(0).path("message").path("content");
            return content.isMissingNode() ? "" : content.asText();
        } catch (Exception ex) {
            log.error("Assistant model call failed: {}", ex.getMessage());
            throw new IllegalStateException("assistant model call failed: " + ex.getMessage(), ex);
        }
    }

    private String endpoint() {
        String base = cfg.baseUrl().replaceAll("/+$", "");
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }
}
