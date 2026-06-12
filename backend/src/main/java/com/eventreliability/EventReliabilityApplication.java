package com.eventreliability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Event Reliability Platform — the single backend monolith ("Brod").
 *
 * <p>This one Spring Boot service contains every internal module of the platform as Java
 * packages (not microservices): ingestion, classification, retry/scheduling, remediation,
 * state &amp; views, audit, pattern detection, control plane, API/streaming and security.
 * It is deployed as N replicas in a single Kafka consumer group for HA and horizontal scale.
 *
 * <p>{@code @EnableScheduling} is enabled <em>only</em> for housekeeping (stale-case sweeps).
 * The retry clock is intentionally NOT driven by {@code @Scheduled} — see the retry package —
 * because under multiple instances every replica's timer would fire independently and cause
 * duplicate/racing retries. Retry timing is data-driven (from {@code x-eligible-at}) and
 * decided by the partition-owning consumer.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableKafka
@EnableKafkaStreams
@EnableScheduling
public class EventReliabilityApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventReliabilityApplication.class, args);
    }
}
