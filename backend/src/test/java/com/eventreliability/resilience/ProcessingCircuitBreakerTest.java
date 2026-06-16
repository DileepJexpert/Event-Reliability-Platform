package com.eventreliability.resilience;

import java.time.Duration;

import com.eventreliability.config.ReliabilityProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the sliding-window processing circuit breaker. */
class ProcessingCircuitBreakerTest {

    private static ProcessingCircuitBreaker breaker(boolean enabled, int threshold, Duration window) {
        return new ProcessingCircuitBreaker(
                new ReliabilityProperties.CircuitBreaker(enabled, threshold, window, 0, Duration.ofSeconds(1)));
    }

    @Test
    void opensOnlyOnTheThresholdFailureWithinTheWindow() {
        ProcessingCircuitBreaker cb = breaker(true, 3, Duration.ofMinutes(5));

        assertThat(cb.onFailure()).isFalse(); // 1
        assertThat(cb.onFailure()).isFalse(); // 2
        assertThat(cb.onFailure()).isTrue();  // 3 -> opens (transition)
        assertThat(cb.isOpen()).isTrue();
        assertThat(cb.onFailure()).isFalse(); // already open -> no re-trip
    }

    @Test
    void resetClosesTheBreaker() {
        ProcessingCircuitBreaker cb = breaker(true, 1, Duration.ofMinutes(5));
        assertThat(cb.onFailure()).isTrue();
        assertThat(cb.isOpen()).isTrue();

        cb.reset();
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    void disabledNeverOpens() {
        ProcessingCircuitBreaker cb = breaker(false, 1, Duration.ofMinutes(5));
        assertThat(cb.onFailure()).isFalse();
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    void failuesOlderThanTheWindowAreEvicted() throws Exception {
        ProcessingCircuitBreaker cb = breaker(true, 2, Duration.ofMillis(50));
        assertThat(cb.onFailure()).isFalse(); // 1
        Thread.sleep(80);                      // first failure ages out of the window
        assertThat(cb.onFailure()).isFalse(); // count is back to 1, not 2
        assertThat(cb.isOpen()).isFalse();
    }
}
