package com.eventreliability.api;

import java.util.List;
import java.util.Map;

import com.eventreliability.resilience.ProcessingCircuitBreaker;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational admin endpoints (§17). After the processing circuit breaker has paused consumption
 * (repeated self-failures), an operator investigates and then resumes via this API — no redeploy
 * needed. Mutating; OPERATOR-only under the {@code secure} chain.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ProcessingCircuitBreaker breaker;
    private final ObjectProvider<KafkaListenerEndpointRegistry> registry;

    public AdminController(ProcessingCircuitBreaker breaker,
                          ObjectProvider<KafkaListenerEndpointRegistry> registry) {
        this.breaker = breaker;
        this.registry = registry;
    }

    /** {@code GET /api/admin/consumers} — breaker state + each listener container's running/paused state. */
    @GetMapping("/consumers")
    public Map<String, Object> status() {
        List<Map<String, Object>> consumers = containers().stream()
                .map(c -> Map.<String, Object>of(
                        "id", String.valueOf(c.getListenerId()),
                        "running", c.isRunning(),
                        "paused", c.isContainerPaused()))
                .toList();
        return Map.of("breakerOpen", breaker.isOpen(), "consumers", consumers);
    }

    /** {@code POST /api/admin/consumers/resume} — resume paused consumers and close the breaker. */
    @PostMapping("/consumers/resume")
    public Map<String, Object> resume() {
        int resumed = 0;
        for (MessageListenerContainer container : containers()) {
            if (container.isContainerPaused()) {
                container.resume();
                resumed++;
            }
        }
        breaker.reset();
        return Map.of("resumed", resumed, "breakerOpen", breaker.isOpen());
    }

    private List<MessageListenerContainer> containers() {
        KafkaListenerEndpointRegistry reg = registry.getIfAvailable();
        return reg == null ? List.of() : List.copyOf(reg.getListenerContainers());
    }
}
