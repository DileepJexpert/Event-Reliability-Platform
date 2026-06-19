package com.eventreliability;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.eventreliability.api.dto.ComplianceRow;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Compliance export: failures are exported as a JSON / CSV disposition register (metadata only).
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
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-compliance-${random.uuid}"
})
class ComplianceExportIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ReadModels readModels;

    @Test
    void exportsFailuresAsJsonAndCsv() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        intake("comp-1", "payments.events");
        intake("comp-2", "orders.events");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.allFailures().size()).isGreaterThanOrEqualTo(2));

        // JSON register
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ResponseEntity<List<ComplianceRow>> json = rest.exchange(
                    "/api/compliance/export", HttpMethod.GET, auth(),
                    new ParameterizedTypeReference<List<ComplianceRow>>() {
                    });
            assertThat(json.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(json.getBody()).extracting(ComplianceRow::correlationId).contains("comp-1", "comp-2");
        });

        // CSV register
        ResponseEntity<String> csv = rest.exchange(
                "/api/compliance/export?format=csv", HttpMethod.GET, auth(), String.class);
        assertThat(csv.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(csv.getBody()).startsWith("correlationId,firstFailedAt,state");
        assertThat(csv.getBody()).contains("comp-1").contains("comp-2").contains("payments.events");
    }

    private HttpEntity<Void> auth() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(CurrentUser.ACTOR_HEADER, "auditor");
        return new HttpEntity<>(headers);
    }

    private void intake(String correlationId, String source) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(CurrentUser.ACTOR_HEADER, "alice");
        Map<String, Object> body = Map.of("correlationId", correlationId, "source", source,
                "sourceApp", "svc", "exceptionClass", "com.bank.ComplianceException", "payload", "{}");
        ResponseEntity<String> res = rest.exchange("/api/failures", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
