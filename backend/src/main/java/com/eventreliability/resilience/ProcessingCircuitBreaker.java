package com.eventreliability.resilience;

import java.util.ArrayDeque;
import java.util.Deque;

import com.eventreliability.config.ReliabilityProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A sliding-window circuit breaker for the platform's OWN message processing — NOT business failures,
 * which are triaged normally. If Brod's listeners throw repeatedly within the configured window (a bug,
 * corrupt state, a serialization fault), the breaker "opens" so the caller can pause consumption and
 * raise a CRITICAL alert instead of silently skipping records ("stop-on-error" safety valve). Closed
 * again by a manual resume.
 */
@Component
public class ProcessingCircuitBreaker {

    private final boolean enabled;
    private final int threshold;
    private final long windowMs;
    private final Deque<Long> failures = new ArrayDeque<>();
    private volatile boolean open;

    @Autowired
    public ProcessingCircuitBreaker(ReliabilityProperties props) {
        this(props.circuitBreaker());
    }

    ProcessingCircuitBreaker(ReliabilityProperties.CircuitBreaker cfg) {
        this.enabled = cfg.enabled();
        this.threshold = Math.max(1, cfg.failureThreshold());
        this.windowMs = Math.max(1L, cfg.window().toMillis());
    }

    /** Record a processing failure; returns {@code true} only on the transition that opens the breaker. */
    public synchronized boolean onFailure() {
        if (!enabled || open) {
            return false;
        }
        long now = System.currentTimeMillis();
        failures.addLast(now);
        while (!failures.isEmpty() && now - failures.peekFirst() > windowMs) {
            failures.pollFirst();
        }
        if (failures.size() >= threshold) {
            open = true;
            return true;
        }
        return false;
    }

    /** Clear the window and close the breaker (after a manual resume). */
    public synchronized void reset() {
        failures.clear();
        open = false;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
