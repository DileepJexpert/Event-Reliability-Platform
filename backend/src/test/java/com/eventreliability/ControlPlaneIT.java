package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.ActionRequest;
import com.eventreliability.api.dto.ApprovalDto;
import com.eventreliability.api.dto.FailureDetailDto;
import com.eventreliability.api.dto.ReplayRequest;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.AuditEvent;
import com.eventreliability.domain.ControlRequest;
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
 * Drives the maker-checker control plane over real HTTP: a maker raises a replay / bulk-replay
 * request, 4-eyes blocks self-approval, then a different checker approves and the command flows
 * through the control topic, executes, advances state and writes audited actions with both users.
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
        "reliability.replay.allowed-topics=payments.retry",
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
    void makerCheckerSingleReplayOverHttpRedrivesAndAudits() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);
        String id = send("com.bank.MysteryException", "payments.events");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(id).map(r -> r.state()).orElse(null))
                        .isEqualTo(MessageState.PARKED_UNKNOWN));

        // Maker raises a replay request.
        ResponseEntity<ActionAccepted> requested = post("/api/failures/" + id + "/replay", "alice",
                new ReplayRequest("upstream fixed", null, null), ActionAccepted.class);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String requestId = requested.getBody().requestId();
        assertThat(requestId).isNotNull();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(id).map(r -> r.state()).orElse(null))
                        .isEqualTo(MessageState.REPLAY_REQUESTED));

        // Request shows up PENDING in the approvals view.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.controlRequest(requestId).map(ControlRequest::status).orElse(null))
                        .isEqualTo(ControlRequest.Status.PENDING));

        // 4-eyes: the maker cannot approve their own request.
        ResponseEntity<String> selfApprove = post("/api/approvals/" + requestId + "/approve", "alice",
                new ActionRequest("approving my own"), String.class);
        assertThat(selfApprove.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A different checker approves → it executes.
        ResponseEntity<ActionAccepted> approved = post("/api/approvals/" + requestId + "/approve", "bob",
                new ActionRequest("verified, go"), ActionAccepted.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(id).map(r -> r.state()).orElse(null))
                        .isEqualTo(MessageState.REPLAYED));

        // The audit timeline records the maker request, the checker approval and the execution.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            FailureDetailDto detail = rest.getForObject("/api/failures/" + id, FailureDetailDto.class);
            assertThat(detail.auditTimeline()).extracting(AuditEvent::action)
                    .contains("REPLAY_REQUESTED", "REPLAY_APPROVED", "REPLAYED");
            assertThat(detail.auditTimeline()).extracting(AuditEvent::actor).contains("alice", "bob");
        });
    }

    @Test
    void makerCheckerBulkReplayOverHttpRecoversCohortAndResolvesIncident() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        for (int i = 0; i < 4; i++) {
            send("com.bank.orders.SchemaDriftException", "orders.events");
        }

        String[] incidentId = new String[1];
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Incident inc = readModels.allIncidents().stream()
                    .filter(in -> in.rootCause().contains("SchemaDriftException"))
                    .findFirst().orElse(null);
            assertThat(inc).isNotNull();
            assertThat(inc.status()).isEqualTo(Incident.ACTIVE);
            incidentId[0] = inc.id();
        });

        // Maker raises the bulk-replay request; a checker approves it.
        ResponseEntity<ActionAccepted> requested = post("/api/incidents/" + incidentId[0] + "/bulk-replay",
                "alice", new ReplayRequest("upstream schema rolled back", null, null), ActionAccepted.class);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String requestId = requested.getBody().requestId();
        assertThat(requestId).isNotNull();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.controlRequest(requestId).map(ControlRequest::status).orElse(null))
                        .isEqualTo(ControlRequest.Status.PENDING));

        ResponseEntity<ActionAccepted> approved = post("/api/approvals/" + requestId + "/approve", "bob",
                new ActionRequest("approved"), ActionAccepted.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

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

    @Test
    void replayCanCorrectPayloadAndRedirectToAnAllowedTopic() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);
        String id = send("com.bank.MysteryException", "payments.events");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(id).map(r -> r.state()).orElse(null))
                        .isEqualTo(MessageState.PARKED_UNKNOWN));

        // A target topic that is not allow-listed is rejected up front (400).
        ResponseEntity<String> denied = post("/api/failures/" + id + "/replay", "alice",
                new ReplayRequest("redirect", "not.allowed.topic", null), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Maker corrects the payload and redirects to an allow-listed topic.
        String corrected = Base64.getEncoder()
                .encodeToString("{\"v\":2,\"fixed\":true}".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<ActionAccepted> requested = post("/api/failures/" + id + "/replay", "alice",
                new ReplayRequest("corrected & redirected", "payments.retry", corrected), ActionAccepted.class);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String requestId = requested.getBody().requestId();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.controlRequest(requestId).map(ControlRequest::status).orElse(null))
                        .isEqualTo(ControlRequest.Status.PENDING));

        // The checker sees the correction + redirect before approving.
        ApprovalDto dto = rest.getForObject("/api/approvals/" + requestId, ApprovalDto.class);
        assertThat(dto.targetTopic()).isEqualTo("payments.retry");
        assertThat(dto.payloadEdited()).isTrue();
        assertThat(dto.payloadOverrideBase64()).isEqualTo(corrected);

        ResponseEntity<ActionAccepted> approved = post("/api/approvals/" + requestId + "/approve", "bob",
                new ActionRequest("ok"), ActionAccepted.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(id).map(r -> r.state()).orElse(null))
                        .isEqualTo(MessageState.REPLAYED));

        // The execution audit records the redirect + the correction.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            FailureDetailDto detail = rest.getForObject("/api/failures/" + id, FailureDetailDto.class);
            assertThat(detail.auditTimeline())
                    .anyMatch(e -> "REPLAYED".equals(e.action()) && e.detail() != null
                            && e.detail().contains("payments.retry") && e.detail().contains("payload corrected"));
        });
    }

    private <T> ResponseEntity<T> post(String url, String actor, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(com.eventreliability.security.CurrentUser.ACTOR_HEADER, actor);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), type);
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
