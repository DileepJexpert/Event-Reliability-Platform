package com.eventreliability;

import java.time.Duration;
import java.util.Map;

import com.eventreliability.api.dto.ExposureDto;
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
 * Financial exposure: the platform extracts a business amount + currency from each stuck failure's
 * JSON payload and sums it into a "value at risk" figure, grouped by currency and topic.
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
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-exposure-${random.uuid}"
})
class ExposureIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ReadModels readModels;

    @Test
    void sumsAtRiskAmountByCurrencyTopicAndTopExposures() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        intake("payments.events", "com.bank.TimeoutException", "{\"amount\": 1000.50, \"currency\": \"USD\"}");
        intake("payments.events", "com.bank.TimeoutException", "{\"amount\": 250, \"currency\": \"USD\"}");
        intake("orders.events", "com.bank.SchemaException", "{\"transactionAmount\": \"5000.00\", \"currency\": \"EUR\"}");
        // A payload with no recognizable amount — counted as "without amount", not at-risk value.
        intake("orders.events", "com.bank.SchemaException", "{\"note\": \"no money here\"}");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.allFailures().size()).isGreaterThanOrEqualTo(4));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ExposureDto dto = rest.getForObject("/api/exposure", ExposureDto.class);
            assertThat(dto).isNotNull();
            assertThat(dto.atRiskCount()).isEqualTo(3);
            assertThat(dto.withoutAmount()).isEqualTo(1);

            assertThat(dto.atRiskByCurrency()).containsKeys("USD", "EUR");
            assertThat(dto.atRiskByCurrency().get("USD")).isEqualTo(1250.50);
            assertThat(dto.atRiskByCurrency().get("EUR")).isEqualTo(5000.00);

            // Biggest single stuck exposure surfaces first.
            assertThat(dto.topExposures().get(0).amount()).isEqualTo(5000.00);

            // payments.events groups two USD failures.
            assertThat(dto.byTopic())
                    .anyMatch(g -> g.name().equals("payments.events") && g.count() == 2
                            && g.amountByCurrency().get("USD").equals(1250.50));
        });
    }

    private void intake(String source, String exceptionClass, String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(CurrentUser.ACTOR_HEADER, "alice");
        Map<String, Object> body = Map.of(
                "source", source, "sourceApp", "svc",
                "exceptionClass", exceptionClass, "payload", payload);
        ResponseEntity<String> res = rest.exchange("/api/failures", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
