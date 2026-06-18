package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.eventreliability.api.dto.AnomalyDto;
import com.eventreliability.api.dto.AnomalyItem;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.streams.ReadModels;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Adaptive anomaly detection: a spike in a series is flagged relative to that series' OWN baseline,
 * not a fixed global threshold. We seed a low historical rate (backdated {@code x-first-failed-at}) and
 * a recent burst of the same signature, then assert the burst is reported as an anomaly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "reliability.topics.partitions=1",
        "reliability.topics.replication-factor=1",
        "reliability.pattern.threshold=100",
        "reliability.pattern.window=120s",
        "reliability.pattern.grace=2s",
        "reliability.anomaly.bucket=5m",
        "reliability.anomaly.lookback=30m",
        "reliability.anomaly.min-count=3",
        "reliability.anomaly.sensitivity=2.0",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-anomaly-${random.uuid}"
})
class AnomalyIT {

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;
    @Autowired
    private TopicNames topics;
    @Autowired
    private ReadModels readModels;
    @Autowired
    private TestRestTemplate rest;

    @Test
    void flagsRecentSpikeAgainstSeriesBaseline() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        long now = System.currentTimeMillis();
        long min = 60_000L;

        // Baseline: a low, sparse historical rate of the same root cause (distinct 5-min buckets).
        send("com.bank.AnomalyException", "ledger.events", now - 8 * min);
        send("com.bank.AnomalyException", "ledger.events", now - 13 * min);
        send("com.bank.AnomalyException", "ledger.events", now - 21 * min);
        // Recent burst: 8 of the same root cause in the latest bucket.
        for (int i = 0; i < 8; i++) {
            send("com.bank.AnomalyException", "ledger.events", now);
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.allFailures().size()).isGreaterThanOrEqualTo(11));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            AnomalyDto dto = rest.getForObject("/api/anomalies", AnomalyDto.class);
            assertThat(dto).isNotNull();
            assertThat(dto.anomalies()).isNotEmpty();

            AnomalyItem hit = dto.anomalies().stream()
                    .filter(a -> a.key().contains("ledger.events") || a.key().contains("AnomalyException"))
                    .findFirst().orElse(null);
            assertThat(hit).isNotNull();
            assertThat(hit.recentCount()).isGreaterThanOrEqualTo(8);
            // The burst sits well above the adaptive threshold derived from the quiet baseline.
            assertThat((double) hit.recentCount()).isGreaterThan(hit.expected());
            assertThat(hit.sampleCorrelationId()).isNotBlank();
        });
    }

    private void send(String exceptionClass, String topic, long firstFailedAt) {
        String id = "anom-" + UUID.randomUUID();
        RecordHeaders h = new RecordHeaders();
        h.add(FailureHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8));
        h.add(FailureHeaders.ORIGINAL_TOPIC, topic.getBytes(StandardCharsets.UTF_8));
        h.add(FailureHeaders.EXCEPTION_CLASS, exceptionClass.getBytes(StandardCharsets.UTF_8));
        h.add(FailureHeaders.ATTEMPT_COUNT, "1".getBytes(StandardCharsets.UTF_8));
        h.add(FailureHeaders.FIRST_FAILED_AT, Long.toString(firstFailedAt).getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, id,
                "payload".getBytes(StandardCharsets.UTF_8), h));
    }
}
