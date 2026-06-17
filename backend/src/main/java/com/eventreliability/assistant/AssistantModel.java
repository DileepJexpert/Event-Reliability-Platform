package com.eventreliability.assistant;

/**
 * Abstraction over the chat model that backs the operations assistant. Deliberately provider-agnostic:
 * the default implementation targets a self-hosted, OpenAI-compatible endpoint (the bank's own model),
 * but any backend can be dropped in. Brod never depends on a specific vendor.
 */
public interface AssistantModel {

    /** Whether a model is configured and reachable enough to attempt a call. */
    boolean available();

    /** Single-turn completion: a system instruction + the user prompt, returns the model's answer. */
    String chat(String systemPrompt, String userPrompt);
}
