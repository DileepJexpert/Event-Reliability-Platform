package com.eventreliability.config;

import com.eventreliability.resilience.CircuitBreakerRecoverer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka wiring not covered by Spring Boot auto-configuration.
 *
 * <p>The platform standardises on {@code <String, byte[]>} for every topic: the key is the
 * correlation id (or a signature) and the value is opaque bytes — business payloads pass through
 * untouched, and the platform's own control records are JSON encoded to bytes via {@link
 * com.eventreliability.common.JsonCodec}. Producer/consumer factories, the default listener
 * container factory and {@code KafkaTemplate} all come from auto-config driven by {@code
 * spring.kafka.*} (idempotent producer, manual offset management, byte[] serdes).
 */
@Configuration
public class KafkaConfig {

    /**
     * A MANUAL-ack listener container factory used by the tiered-retry delay loop (§10, §18.1).
     * Manual acknowledgement lets the retry listener pause/seek (via {@code Acknowledgment.nack(Duration)})
     * for not-yet-eligible messages while the consumer keeps polling and stays in the group — instead
     * of committing the offset and losing the message, or sleeping the poll thread.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> manualAckListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        @SuppressWarnings({"unchecked", "rawtypes"})
        ConsumerFactory<String, byte[]> cf = (ConsumerFactory) consumerFactory;
        factory.setConsumerFactory(cf);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }

    /**
     * Error handler for the platform's listeners: a few quick retries, then the circuit-breaking
     * recoverer (pause every consumer + raise a CRITICAL alert on repeated self-failures). Spring Boot
     * applies this single {@code CommonErrorHandler} bean to the auto-configured listener container
     * factory used by the ingestion / classification / control / feed listeners.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(CircuitBreakerRecoverer recoverer,
                                                 ReliabilityProperties props) {
        ReliabilityProperties.CircuitBreaker cfg = props.circuitBreaker();
        FixedBackOff backOff = new FixedBackOff(Math.max(0L, cfg.retryInterval().toMillis()),
                Math.max(0L, (long) cfg.retries()));
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
