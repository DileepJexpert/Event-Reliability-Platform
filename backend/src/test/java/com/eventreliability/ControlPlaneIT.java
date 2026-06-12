package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.ActionRequest;
import com.eventreliability.api.dto.FailureDetailDto;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.AuditEvent;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.Incident;
import com.eventreliability.domain.MessageState;
import com.eventreliability.streams.ReadModels;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the control plane over real HTTP: a single replay and an incident bulk-replay, asserting the
 * command flows through the control topic, executes, advances state and writes audited actions with
 * the acting user.
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
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-control-${random.uuid}"
})
class ControlPlaneIT {

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;
    @Autowired
    private TopicNames topics;
    @Autowired
    private ReadModels readModels;
    @Autowired
    private TestRestTemplate rest;

    @Test
    void singleReplayOverHttpRedrivesAndAudits() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);
        String id = send("com.bank.MysteryException", "payments.events");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(id).map(r -> r.state()).orElse(null))
                        .isEqualTo(MessageState.PARKED_UNKNOWN));

        ResponseEntity<ActionAccepted> resp = rest.postForEntity(
                "/api/failures/" + id + "/replay", new ActionRequest("operator fixed upstream"),
                ActionAccepted.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().action()).isEqualTo("replay");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(id).map(r -> r.state()).orElse(null))
                        .isEqualTo(MessageState.REPLAYED));

        // The audit timeline (served over HTTP, via the aggregated views.audit GlobalKTable) records
        // both the operator request and the execution — poll until that view catches up.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            FailureDetailDto detail = rest.getForObject("/api/failures/" + id, FailureDetailDto.class);
            assertThat(detail.auditTimeline()).extracting(AuditEvent::action)
                    .contains("REPLAY_REQUESTED", "REPLAYED");
        });
    }

    @Test
    void bulkReplayOverHttpRecoversCohortAndResolvesIncident() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        for (int i = 0; i < 4; i++) {
            send("com.bank.orders.SchemaDriftException", "orders.events");
        }

        // Wait for the incident to be raised.
        String[] incidentId = new String[1];
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Incident inc = readModels.allIncidents().stream()
                    .filter(in -> in.rootCause().contains("SchemaDriftException"))
                    .findFirst().orElse(null);
            assertThat(inc).isNotNull();
            assertThat(inc.status()).isEqualTo(Incident.ACTIVE);
            incidentId[0] = inc.id();
        });

        ResponseEntity<ActionAccepted> resp = rest.postForEntity(
                "/api/incidents/" + incidentId[0] + "/bulk-replay",
                new ActionRequest("upstream schema rolled back"), ActionAccepted.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Incident resolves and the cohort is moved to REPLAYED.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Incident inc = readModels.incident(incidentId[0]).orElse(null);
            assertThat(inc).isNotNull();
            assertThat(inc.status()).isEqualTo(Incident.RESOLVED);
            long replayed = readModels.allFailures().stream()
                    .filter(r -> "orders.events".equals(r.originalTopic()))
                    .filter(r -> r.state() == MessageState.REPLAYED)
                    .count();
            assertThat(replayed).isGreaterThanOrEqualTo(3);
        });
    }

    private String send(String exceptionClass, String originalTopic) {
        String id = "corr-" + UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add(FailureHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_TOPIC, originalTopic.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.EXCEPTION_CLASS, exceptionClass.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ATTEMPT_COUNT, "1".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, id, "{\"v\":1}".getBytes(StandardCharsets.UTF_8), headers));
        return id;
    }
}
