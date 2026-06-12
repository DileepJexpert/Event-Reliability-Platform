package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.eventreliability.config.TopicNames;
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
 * Boots the entire Spring context against an in-process Kafka broker and drives one failure through
 * ingestion. This is the core wiring smoke test: it proves the topic provisioner, the byte[]
 * producer/consumer wiring, the ingestion listener, the compacted state topic and the GlobalKTable
 * read model all start and cooperate end to end.
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
    void ingestedFailureBecomesQueryableState() {
        // Read model must come up.
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        String correlationId = "corr-" + UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add(FailureHeaders.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_TOPIC, "payments.events".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.EXCEPTION_CLASS,
                "java.net.SocketTimeoutException".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ATTEMPT_COUNT, "1".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.SOURCE_APP, "payment-service".getBytes(StandardCharsets.UTF_8));

        byte[] payload = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, correlationId, payload, headers));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            FailureRecord record = readModels.failure(correlationId).orElse(null);
            assertThat(record).isNotNull();
            assertThat(record.state()).isEqualTo(MessageState.RECEIVED);
            assertThat(record.originalTopic()).isEqualTo("payments.events");
            assertThat(record.rootCauseSignature()).contains("SocketTimeoutException");
        });
    }
}
