package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.ActionRequest;
import com.eventreliability.api.dto.ReplayRequest;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.ControlRequest;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.Incident;
import com.eventreliability.domain.MessageState;
import com.eventreliability.streams.ReadModels;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Ordering-safe replay (Confluent error-handling pattern #4 — preserve order across the redirect).
 *
 * <p>Brod sits on the DLQ side, so it cannot hold back source-topic events; what it CAN guarantee is
 * that <em>its own</em> re-drive never violates per-key order on the destination partition. Two rules:
 * <ul>
 *   <li>Single replay is refused (409) when a <em>newer</em> failure with the same {@code x-original-key}
 *       still exists on the same source topic — replaying the older one would land after the newer.</li>
 *   <li>Bulk replay dispatches the cohort sorted by {@code (originalTopic, originalKey, originalOffset)},
 *       so same-key records are republished in source-offset order even when they arrived at Brod out
 *       of order.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "reliability.topics.partitions=1",
        "reliability.topics.replication-factor=1",
        "reliability.pattern.threshold=3",
        "reliability.pattern.window=120s",
        "reliability.pattern.grace=2s",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-ordering-${random.uuid}"
})
class OrderingReplayIT {

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;
    @Autowired
    private TopicNames topics;
    @Autowired
    private ReadModels readModels;
    @Autowired
    private TestRestTemplate rest;
    @Value("${spring.embedded.kafka.brokers}")
    private String brokers;

    /** Replaying an older same-key failure when a newer one is still stuck is refused with 409. */
    @Test
    void singleReplayRefusedWhenNewerSameKeyFailureExists() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        String older = sendWithKey("com.bank.OrderingException", "ledger.events", "acct-1", 10L);
        String newer = sendWithKey("com.bank.OrderingException", "ledger.events", "acct-1", 20L);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(readModels.failure(older).map(r -> r.state()).orElse(null))
                    .isEqualTo(MessageState.PARKED_UNKNOWN);
            assertThat(readModels.failure(newer).map(r -> r.state()).orElse(null))
                    .isEqualTo(MessageState.PARKED_UNKNOWN);
        });

        // Replaying the OLDER record while a newer one is stuck on the same key → 409.
        ResponseEntity<String> denied = post("/api/failures/" + older + "/replay", "alice",
                new ReplayRequest("upstream fixed", null, null), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(denied.getBody()).contains("acct-1").contains(newer);

        // Replaying the NEWER record is accepted (no newer same-key failure exists).
        ResponseEntity<ActionAccepted> ok = post("/api/failures/" + newer + "/replay", "alice",
                new ReplayRequest("upstream fixed", null, null), ActionAccepted.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    /** Bulk replay republishes same-key records in source-offset order, regardless of arrival order. */
    @Test
    void bulkReplayDispatchesSameKeyInOriginalOffsetOrder() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        // Send 4 same-cause failures on the same key, with distinct original offsets, in SCRAMBLED order.
        String topic = "trade.events";
        Map<String, Long> idToOffset = new HashMap<>();
        long[] scrambledOffsets = {30, 10, 40, 20};
        for (long off : scrambledOffsets) {
            idToOffset.put(sendWithKey("com.bank.SchemaDriftException", topic, "trade-7", off), off);
        }

        // Wait for the incident.
        String[] incidentId = new String[1];
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Incident inc = readModels.allIncidents().stream()
                    .filter(in -> in.rootCause().contains("SchemaDriftException"))
                    .filter(in -> topic.equals(in.sourceTopic()))
                    .findFirst().orElse(null);
            assertThat(inc).isNotNull();
            assertThat(inc.status()).isEqualTo(Incident.ACTIVE);
            incidentId[0] = inc.id();
        });

        // Subscribe a consumer to the destination BEFORE approving, so we capture every re-driven record.
        try (KafkaConsumer<String, byte[]> consumer = newDestinationConsumer(topic)) {

            // Maker raises bulk-replay; checker approves.
            ResponseEntity<ActionAccepted> requested = post("/api/incidents/" + incidentId[0] + "/bulk-replay",
                    "alice", new ReplayRequest("upstream rolled back", null, null), ActionAccepted.class);
            assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            String requestId = requested.getBody().requestId();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(readModels.controlRequest(requestId).map(ControlRequest::status).orElse(null))
                            .isEqualTo(ControlRequest.Status.PENDING));

            ResponseEntity<ActionAccepted> approved = post("/api/approvals/" + requestId + "/approve",
                    "bob", new ActionRequest("verified"), ActionAccepted.class);
            assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            List<ConsumerRecord<String, byte[]>> captured = pollAtLeast(consumer, 4, Duration.ofSeconds(45));
            List<Long> redriveOriginalOffsets = captured.stream()
                    .filter(r -> "trade-7".equals(r.key()))
                    .map(r -> Long.parseLong(new String(
                            r.headers().lastHeader(FailureHeaders.ORIGINAL_OFFSET).value(), StandardCharsets.UTF_8)))
                    .toList();

            // Despite scrambled arrival, re-drive preserves source order on the same key: 10, 20, 30, 40.
            assertThat(redriveOriginalOffsets).containsExactly(10L, 20L, 30L, 40L);
        }
    }

    // ---------------- helpers ----------------

    private String sendWithKey(String exceptionClass, String originalTopic, String originalKey, long originalOffset) {
        String id = "corr-" + UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add(FailureHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_TOPIC, originalTopic.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_KEY, originalKey.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_OFFSET, Long.toString(originalOffset).getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.EXCEPTION_CLASS, exceptionClass.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ATTEMPT_COUNT, "1".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, id,
                ("payload-" + originalOffset).getBytes(StandardCharsets.UTF_8), headers));
        return id;
    }

    private KafkaConsumer<String, byte[]> newDestinationConsumer(String topic) {
        Map<String, Object> p = new HashMap<>();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "ordering-it-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(p);
        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofMillis(500)); // assign + position so subsequent polls capture re-drives
        return consumer;
    }

    private static List<ConsumerRecord<String, byte[]>> pollAtLeast(
            KafkaConsumer<String, byte[]> consumer, int min, Duration timeout) {
        List<ConsumerRecord<String, byte[]>> captured = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (captured.size() < min && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, byte[]> batch = consumer.poll(Duration.ofMillis(500));
            batch.forEach(captured::add);
        }
        return captured;
    }

    private <T> ResponseEntity<T> post(String url, String actor, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(com.eventreliability.security.CurrentUser.ACTOR_HEADER, actor);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }
}
