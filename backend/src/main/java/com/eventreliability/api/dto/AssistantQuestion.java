package com.eventreliability.api.dto;

/** A natural-language question for the operations assistant ({@code POST /api/assistant/ask}). */
public record AssistantQuestion(String question) {
}
