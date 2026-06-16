package com.eventreliability.resilience;

import java.time.Duration;
import java.util.List;

import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.observability.NotificationSender;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test for the circuit-breaking recoverer: pause + alert exactly once when the breaker opens. */
class CircuitBreakerRecovererTest {

    @Test
    @SuppressWarnings("unchecked")
    void pausesEveryConsumerAndAlertsOnceWhenBreakerOpens() {
        // Breaker opens on the 2nd failure.
        ProcessingCircuitBreaker breaker = new ProcessingCircuitBreaker(
                new ReliabilityProperties.CircuitBreaker(true, 2, Duration.ofMinutes(5), 0, Duration.ofSeconds(1)));

        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        when(container.isContainerPaused()).thenReturn(false);
        KafkaListenerEndpointRegistry reg = mock(KafkaListenerEndpointRegistry.class);
        when(reg.getListenerContainers()).thenReturn(List.of(container));
        ObjectProvider<KafkaListenerEndpointRegistry> registry = mock(ObjectProvider.class);
        when(registry.getIfAvailable()).thenReturn(reg);

        NotificationSender sender = mock(NotificationSender.class);
        ReliabilityProperties props = mock(ReliabilityProperties.class);
        when(props.notifier()).thenReturn(new ReliabilityProperties.Notifier(true, "log", null));

        CircuitBreakerRecoverer recoverer = new CircuitBreakerRecoverer(breaker, registry, sender, props);
        ConsumerRecord<String, byte[]> rec =
                new ConsumerRecord<>("reliability.dlq.inbound", 0, 0L, "k", new byte[0]);

        recoverer.accept(rec, new RuntimeException("boom"));   // 1st: below threshold
        verify(container, never()).pause();
        verifyNoInteractions(sender);

        recoverer.accept(rec, new RuntimeException("boom"));   // 2nd: opens -> pause + alert
        verify(container, times(1)).pause();
        verify(sender, times(1)).send(any());

        recoverer.accept(rec, new RuntimeException("boom"));   // 3rd: already open -> no extra pause/alert
        verify(container, times(1)).pause();
        verify(sender, times(1)).send(any());
    }
}
