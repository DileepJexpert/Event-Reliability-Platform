package com.eventreliability;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.eventreliability.api.dto.ReconciliationDto;
import com.eventreliability.domain.MessageState;
import com.eventreliability.security.CurrentUser;
import com.eventreliability.streams.ReadModels;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Reconciliation / completeness: of the captured failures, those re-driven/resolved count as completed
 * and the rest are the open reconciliation gap. We seed three failures on one source, replay one (so it
 * reaches REPLAYED = completed), and assert the completeness split.
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
        // Execute replays directly (no checker) so one failure reaches REPLAYED within the test.
        "reliability.replay.approval-required=false",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-recon-${random.uuid}"
})
class ReconciliationIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ReadModels readModels;

    @Test
    void splitsCompletedVersusOpen() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        String id1 = "recon-" + UUID.randomUUID();
        String id2 = "recon-" + UUID.randomUUID();
        String id3 = "recon-" + UUID.randomUUID();
        intake(id1, "settlement.events", "settlement-svc");
        intake(id2, "settlement.events", "settlement-svc");
        intake(id3, "settlement.events", "settlement-svc");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(readModels.failure(id1)).isPresent();
            assertThat(readModels.failure(id2)).isPresent();
            assertThat(readModels.failure(id3)).isPresent();
        });

        // Replay one -> it reaches REPLAYED (= completed).
        ResponseEntity<String> replay = post("/api/failures/" + id1 + "/replay",
                Map.of("reason", "reprocessed"));
        assertThat(replay.getStatusCode().is2xxSuccessful()).isTrue();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(id1).map(r -> r.state()).orElse(null))
                        .isEqualTo(MessageState.REPLAYED));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ReconciliationDto dto = rest.getForObject("/api/reconciliation", ReconciliationDto.class);
            assertThat(dto).isNotNull();
            assertThat(dto.totalCaptured()).isEqualTo(3);
            assertThat(dto.completed()).isEqualTo(1);
            assertThat(dto.open()).isEqualTo(2);
            assertThat(dto.completionRate()).isBetween(0.33, 0.34);
            assertThat(dto.byTopic())
                    .anyMatch(g -> g.name().equals("settlement.events") && g.total() == 3
                            && g.completed() == 1 && g.open() == 2);
            assertThat(dto.oldestOpen()).hasSize(2);
        });
    }

    private void intake(String correlationId, String source, String sourceApp) {
        ResponseEntity<String> res = post("/api/failures", Map.of(
                "correlationId", correlationId, "source", source, "sourceApp", sourceApp,
                "exceptionClass", "com.bank.SettlementException", "payload", "{\"id\":\"x\"}"));
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private ResponseEntity<String> post(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(CurrentUser.ACTOR_HEADER, "alice");
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
