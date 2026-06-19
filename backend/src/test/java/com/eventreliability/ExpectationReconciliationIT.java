package com.eventreliability;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.eventreliability.api.dto.ExpectationReconciliationDto;
import com.eventreliability.security.CurrentUser;
import com.eventreliability.streams.ReadModels;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Declared-expectation reconciliation: a producer declares "expect N events on a source"; Brod
 * reconciles that against the failures it captured and reports the shortfall (still-stuck) and the
 * presumed completion.
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
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-expect-${random.uuid}"
})
class ExpectationReconciliationIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ReadModels readModels;

    @Test
    void reconcilesDeclaredExpectedAgainstCapturedFailures() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        // Declare: we expect 100 events processed on settlement.events.
        ResponseEntity<ExpectationReconciliationDto> declared = post(
                "/api/reconciliation/expectations",
                Map.of("key", "batch-eod", "source", "settlement.events", "expectedCount", 100,
                        "label", "EOD settlement"),
                ExpectationReconciliationDto.class);
        assertThat(declared.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(declared.getBody().expectedCount()).isEqualTo(100);
        assertThat(declared.getBody().status()).isEqualTo("RECONCILED"); // no failures yet

        // Three of those events land as failures (still open).
        for (int i = 0; i < 3; i++) {
            intake("settlement.events");
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.allFailures().size()).isGreaterThanOrEqualTo(3));

        // Reconciliation now shows the shortfall: 3 of the 100 are stuck, 97 presumed complete.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ExpectationReconciliationDto dto = list().stream()
                    .filter(d -> d.key().equals("batch-eod")).findFirst().orElse(null);
            assertThat(dto).isNotNull();
            assertThat(dto.expectedCount()).isEqualTo(100);
            assertThat(dto.failed()).isEqualTo(3);
            assertThat(dto.open()).isEqualTo(3);
            assertThat(dto.completed()).isEqualTo(97);
            assertThat(dto.completionRate()).isBetween(0.96, 0.98);
            assertThat(dto.status()).isEqualTo("SHORTFALL");
        });
    }

    private List<ExpectationReconciliationDto> list() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(CurrentUser.ACTOR_HEADER, "alice");
        return rest.exchange("/api/reconciliation/expectations", HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<List<ExpectationReconciliationDto>>() {
                }).getBody();
    }

    private void intake(String source) {
        post("/api/failures", Map.of(
                "correlationId", "expect-" + UUID.randomUUID(), "source", source, "sourceApp", "settlement-svc",
                "exceptionClass", "com.bank.SettlementException", "payload", "{\"id\":\"x\"}"), String.class);
    }

    private <T> ResponseEntity<T> post(String url, Map<String, Object> body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(CurrentUser.ACTOR_HEADER, "alice");
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }
}
