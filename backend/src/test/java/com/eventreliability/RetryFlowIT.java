package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.MessageState;
import com.eventreliability.housekeeping.StaleCaseSweeper;
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
 * Exercises the tiered-retry mechanism end to end with a single fast (1s) tier: a transient failure
 * is scheduled onto the tier topic, the pause/seek delay loop holds it until eligible, re-drives it
 * to the source topic (state RETRYING), and the housekeeping sweep then promotes it to RESOLVED once
 * the short resolve-grace elapses with no re-failure.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "reliability.topics.partitions=1",
        "reliability.topics.replication-factor=1",
        "reliability.retry.tiers[0].name=fast",
        "reliability.retry.tiers[0].delay=1s",
        "reliability.retry.max-pause=2s",
        "reliability.housekeeping.resolve-grace=1s",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-retry-${random.uuid}"
})
class RetryFlowIT {

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;

    @Autowired
    private TopicNames topics;

    @Autowired
    private ReadModels readModels;

    @Autowired
    private StaleCaseSweeper sweeper;

    @Test
    void transientFailureIsScheduledRedrivenAndPresumedResolved() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        String id = "corr-" + UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add(FailureHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_TOPIC, "payments.events".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.EXCEPTION_CLASS, "java.net.SocketTimeoutException".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ATTEMPT_COUNT, "1".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, id, "{}".getBytes(StandardCharsets.UTF_8), headers));

        // Scheduled onto the fast tier.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(id).map(FailureRecord::state).orElse(null))
                        .isIn(MessageState.RETRY_SCHEDULED, MessageState.RETRYING));

        // Delay loop re-drives once eligible -> RETRYING (attempt incremented to 2).
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            FailureRecord r = readModels.failure(id).orElse(null);
            assertThat(r).isNotNull();
            assertThat(r.state()).isEqualTo(MessageState.RETRYING);
            assertThat(r.attemptCount()).isEqualTo(2);
        });

        // Housekeeping presumes success after the resolve-grace with no re-failure.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            sweeper.sweep();
            assertThat(readModels.failure(id).map(FailureRecord::state).orElse(null))
                    .isEqualTo(MessageState.RESOLVED);
        });
    }
}
