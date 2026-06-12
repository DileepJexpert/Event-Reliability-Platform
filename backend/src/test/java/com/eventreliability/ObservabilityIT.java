package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.streams.ReadModels;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the self-contained observability surface (§14): Actuator health, the Prometheus scrape
 * endpoint and the platform's custom Micrometer meters reflecting real activity.
 *
 * <p>{@code @AutoConfigureObservability} opts this test into metrics export — Spring Boot Test
 * disables exporters (including Prometheus) by default; in a real run the endpoint is enabled by the
 * {@code micrometer-registry-prometheus} dependency and the actuator exposure config.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@EmbeddedKafka(partitions = 1, topics = {})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "reliability.topics.partitions=1",
        "reliability.topics.replication-factor=1",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-obs-${random.uuid}"
})
class ObservabilityIT {

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;
    @Autowired
    private TopicNames topics;
    @Autowired
    private ReadModels readModels;
    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthIsUpAndPrometheusExposesPlatformMetrics() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        assertThat(rest.getForObject("/actuator/health", String.class)).contains("UP");

        // Generate activity so the counters move.
        String id = "corr-" + UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add(FailureHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_TOPIC, "payments.events".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.EXCEPTION_CLASS, "java.net.SocketTimeoutException".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ATTEMPT_COUNT, "1".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, id, "{}".getBytes(StandardCharsets.UTF_8), headers));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            String prometheus = rest.getForObject("/actuator/prometheus", String.class);
            assertThat(prometheus).contains("erp_failures_ingested");
            assertThat(prometheus).contains("erp_failures_classified");
            assertThat(prometheus).contains("erp_failures_tracked");
        });
    }
}
