package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
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
 * Boots the entire Spring context against an in-process Kafka broker and drives failures through
 * ingestion → classification → routing. Proves the topic provisioner, the byte[] producer/consumer
 * wiring, the listeners, the compacted state topic and the GlobalKTable read model cooperate end to
 * end, and that each taxonomy class lands in the correct lane.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "reliability.topics.partitions=1",
        "reliability.topics.replication-factor=1",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-test-${random.uuid}"
})
class IngestionFlowIT {

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;

    @Autowired
    private TopicNames topics;

    @Autowired
    private ReadModels readModels;

    @Test
    void transientFailureIsClassifiedAndScheduledForRetry() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);
        String id = send("java.net.SocketTimeoutException", "payments.events", 1);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            FailureRecord record = readModels.failure(id).orElse(null);
            assertThat(record).isNotNull();
            assertThat(record.classification()).isEqualTo(FailureClassification.TRANSIENT);
            assertThat(record.state()).isEqualTo(MessageState.RETRY_SCHEDULED);
            assertThat(record.currentTier()).isEqualTo("5s");
            assertThat(record.rootCauseSignature()).contains("SocketTimeoutException");
        });
    }

    @Test
    void poisonFailureIsQuarantined() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);
        String id = send("org.apache.kafka.common.errors.SerializationException", "orders.events", 1);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            FailureRecord record = readModels.failure(id).orElse(null);
            assertThat(record).isNotNull();
            assertThat(record.classification()).isEqualTo(FailureClassification.POISON);
            assertThat(record.state()).isEqualTo(MessageState.QUARANTINED_POISON);
        });
    }

    @Test
    void businessFailureIsRoutedToOwner() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);
        String id = send("com.bank.payments.AccountFrozenException", "payments.events", 1);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            FailureRecord record = readModels.failure(id).orElse(null);
            assertThat(record).isNotNull();
            assertThat(record.classification()).isEqualTo(FailureClassification.BUSINESS);
            assertThat(record.state()).isEqualTo(MessageState.ROUTED_BUSINESS);
        });
    }

    @Test
    void unknownFailureIsParkedForReview() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);
        String id = send("com.bank.payments.WeirdUndocumentedException", "payments.events", 1);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            FailureRecord record = readModels.failure(id).orElse(null);
            assertThat(record).isNotNull();
            assertThat(record.classification()).isEqualTo(FailureClassification.UNKNOWN);
            assertThat(record.state()).isEqualTo(MessageState.PARKED_UNKNOWN);
        });
    }

    private String send(String exceptionClass, String originalTopic, int attempt) {
        String correlationId = "corr-" + UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add(FailureHeaders.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_TOPIC, originalTopic.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.EXCEPTION_CLASS, exceptionClass.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ATTEMPT_COUNT, Integer.toString(attempt).getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.SOURCE_APP, "payment-service".getBytes(StandardCharsets.UTF_8));
        byte[] payload = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, correlationId, payload, headers));
        return correlationId;
    }
}
