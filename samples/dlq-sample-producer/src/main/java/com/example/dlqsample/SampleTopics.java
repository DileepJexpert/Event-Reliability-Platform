package com.example.dlqsample;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Creates the "original" business topics this sample references in its failure headers
 * ({@code x-original-topic}). The platform RE-DRIVES auto-retried failures — and manual replays — back
 * to the original topic, so those topics must exist on the broker, otherwise the re-drive fails with
 * {@code UNKNOWN_TOPIC_OR_PARTITION} (the broker has auto-create disabled).
 *
 * <p>Spring Boot's auto-configured {@code KafkaAdmin} creates any {@link NewTopic} bean on startup
 * (idempotent — existing topics are left alone), which keeps this demo self-contained.
 */
@Configuration
public class SampleTopics {

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentsTransactions() {
        return topic("payments.transactions");
    }

    @Bean
    NewTopic ordersEvents() {
        return topic("orders.events");
    }

    @Bean
    NewTopic inventoryUpdates() {
        return topic("inventory.updates");
    }

    @Bean
    NewTopic billingDebits() {
        return topic("billing.debits");
    }

    @Bean
    NewTopic sagasEvents() {
        return topic("sagas.events");
    }
}
