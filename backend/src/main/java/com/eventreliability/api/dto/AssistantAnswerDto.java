package com.eventreliability.api.dto;

import java.util.List;

/**
 * The assistant's reply. {@code citations} are the incident/failure ids that were placed in the model's
 * context (so the console can link them); {@code grounded} is false when the assistant is not configured
 * or had no context to work from.
 */
public record AssistantAnswerDto(
        String answer,
        List<String> citations,
        int contextSize,
        boolean grounded) {
}
