package com.eventreliability.observability;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fan-out hub for the console's live feed (§14, §15 — {@code GET /api/stream}). Holds the open
 * {@link SseEmitter}s and broadcasts named JSON events ({@code failure}, {@code incident}) to all of
 * them. Dead emitters are pruned on send. Broadcasts are local to this instance's subscribers; the
 * feed listeners use per-instance consumer groups so every instance sees the full stream.
 */
@Service
public class SseService {

    private static final Logger log = LoggerFactory.getLogger(SseService.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // never time out
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));
        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException ex) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public boolean hasSubscribers() {
        return !emitters.isEmpty();
    }

    public void broadcast(String event, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                emitters.remove(emitter);
                log.debug("Dropped a dead SSE subscriber: {}", ex.getMessage());
            }
        }
    }
}
