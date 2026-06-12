package com.eventreliability.api;

import com.eventreliability.observability.SseService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live feed endpoint (§15 — {@code GET /api/stream}). Returns a Server-Sent Events stream that
 * pushes {@code failure} and {@code incident} events as they happen, so the console dashboard updates
 * in real time without polling.
 */
@RestController
public class StreamController {

    private final SseService sseService;

    public StreamController(SseService sseService) {
        this.sseService = sseService;
    }

    @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return sseService.subscribe();
    }
}
