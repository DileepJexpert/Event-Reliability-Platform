package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.Incident;
import com.eventreliability.streams.ReadModels;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the pattern-detection topology raises a systemic incident once enough failures sharing a
 * root-cause signature land within a window. Threshold is lowered to 3 so a burst of identical
 * failures trips an incident that appears in the incidents GlobalKTable.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "reliability.topics.partitions=1",
        "reliability.topics.replication-factor=1",
        "reliability.pattern.threshold=3",
        "reliability.pattern.window=60s",
        "reliability.pattern.grace=2s",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-pattern-${random.uuid}"
})
class PatternDetectionIT {

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;

    @Autowired
    private TopicNames topics;

    @Autowired
    private ReadModels readModels;

    @Test
    void burstOfIdenticalFailuresRaisesAnIncident() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        // Five failures sharing one signature: SchemaException on orders.events (simulated drift).
        for (int i = 0; i < 5; i++) {
            send("com.bank.orders.SchemaMismatchException", "orders.events");
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Incident incident = readModels.allIncidents().stream()
                    .filter(in -> in.rootCause().contains("SchemaMismatchException"))
                    .findFirst().orElse(null);
            assertThat(incident).isNotNull();
            assertThat(incident.count()).isGreaterThanOrEqualTo(3);
            assertThat(incident.sourceTopic()).isEqualTo("orders.events");
            assertThat(incident.status()).isEqualTo(Incident.ACTIVE);
            assertThat(incident.exampleCorrelationId()).isNotBlank();
        });
    }

    private void send(String exceptionClass, String originalTopic) {
        String id = "corr-" + UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add(FailureHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_TOPIC, originalTopic.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.EXCEPTION_CLASS, exceptionClass.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ATTEMPT_COUNT, "1".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, id, "{}".getBytes(StandardCharsets.UTF_8), headers));
    }
}
