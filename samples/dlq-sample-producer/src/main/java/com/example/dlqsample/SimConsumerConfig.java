package com.example.dlqsample;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Wires the business-topic consumer's retry + dead-letter behaviour. On a processing exception the
 * record is retried with a fixed backoff up to {@code sim.max-attempts} total deliveries; once those
 * are exhausted the recoverer routes the record to the platform DLQ — carrying the real exception and
 * the original topic/partition/offset — via {@link DlqFailureProducer}. Spring Boot auto-wires this
 * {@link DefaultErrorHandler} bean into the @KafkaListener container factory.
 */
@Configuration
public class SimConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(SimConsumerConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DlqFailureProducer dlq,
                                          @Value("${sim.max-attempts:3}") int maxAttempts) {
        // maxAttempts total deliveries = 1 initial + (maxAttempts - 1) retries.
        long retries = Math.max(0, maxAttempts - 1);
        FixedBackOff backOff = new FixedBackOff(1000L, retries);
        return new DefaultErrorHandler(
                (record, exception) -> {
                    @SuppressWarnings("unchecked")
                    ConsumerRecord<String, String> rec = (ConsumerRecord<String, String>) record;
                    log.warn("Retries exhausted ({} attempts) for key={} — routing to DLQ",
                            maxAttempts, rec.key());
                    dlq.sendFromFailedConsume(rec, exception, maxAttempts);
                },
                backOff);
    }
}
