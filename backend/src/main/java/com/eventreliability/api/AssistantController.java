package com.eventreliability.api;

import com.eventreliability.api.dto.AssistantAnswerDto;
import com.eventreliability.api.dto.AssistantQuestion;
import com.eventreliability.assistant.AssistantService;
import com.eventreliability.security.CurrentUser;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations assistant (a self-hosted "Davis CoPilot" for failures). Read-only: operators ask natural-
 * language questions and get cited, grounded answers from Brod's read models (§15). Available to
 * VIEWER/OPERATOR; the query is audited with the acting user.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/ask")
    public AssistantAnswerDto ask(@RequestBody(required = false) AssistantQuestion request) {
        String question = request == null ? null : request.question();
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        return assistant.ask(question.trim(), CurrentUser.name());
    }
}
