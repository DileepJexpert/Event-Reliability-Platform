package com.eventreliability.observability;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-unit coverage of the SSE fan-out hub: subscription tracking and broadcast safety. */
class SseServiceTest {

    private final SseService sse = new SseService();

    @Test
    void tracksSubscribersAndBroadcastsWithoutThrowing() {
        assertThat(sse.hasSubscribers()).isFalse();

        SseEmitter emitter = sse.subscribe();
        assertThat(emitter).isNotNull();
        assertThat(sse.hasSubscribers()).isTrue();

        // Broadcasting to a live (in-memory) subscriber must not throw.
        sse.broadcast("failure", java.util.Map.of("correlationId", "abc", "state", "RECEIVED"));
        sse.broadcast("incident", java.util.Map.of("id", "sig:123"));

        assertThat(sse.hasSubscribers()).isTrue();
    }

    @Test
    void broadcastWithNoSubscribersIsANoOp() {
        sse.broadcast("failure", java.util.Map.of("x", "y"));
        assertThat(sse.hasSubscribers()).isFalse();
    }
}
