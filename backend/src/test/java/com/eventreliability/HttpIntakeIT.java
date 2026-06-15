package com.eventreliability;

import java.time.Duration;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.FailureIntakeRequest;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.streams.ReadModels;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the HTTP "all-error" intake over real HTTP: a non-Kafka producer POSTs a failure to
 * {@code /api/failures}; it is published to the inbound DLQ and flows through the normal ingestion
 * pipeline, landing as a queryable failure record with its source / app / exception preserved. Also
 * covers server-side correlation-id generation and validation of the required fields.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "reliability.topics.partitions=1",
        "reliability.topics.replication-factor=1",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-intake-${random.uuid}"
})
class HttpIntakeIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ReadModels readModels;

    @Test
    void httpSubmittedFailureIsIngestedAndQueryable() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        FailureIntakeRequest req = new FailureIntakeRequest("eod-settle-001", "eod-settlement-batch",
                "settlement-service", "com.bank.batch.RecordRejectedException", "row 42 rejected",
                null, null, "{\"row\":42,\"amount\":1000}");
        ResponseEntity<ActionAccepted> resp = rest.postForEntity("/api/failures", req, ActionAccepted.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().target()).isEqualTo("eod-settle-001");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            FailureRecord rec = readModels.failure("eod-settle-001").orElse(null);
            assertThat(rec).isNotNull();
            assertThat(rec.originalTopic()).isEqualTo("eod-settlement-batch");
            assertThat(rec.sourceApp()).isEqualTo("settlement-service");
            assertThat(rec.exceptionClass()).isEqualTo("com.bank.batch.RecordRejectedException");
            assertThat(rec.payloadBase64()).isNotNull();
        });
    }

    @Test
    void generatesCorrelationIdWhenAbsentAndValidatesRequiredFields() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        // exceptionClass missing -> 400
        ResponseEntity<String> noException = rest.postForEntity("/api/failures",
                new FailureIntakeRequest(null, "payment-gateway", "pay-gw", null, "boom", null, null, "{}"),
                String.class);
        assertThat(noException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // source missing -> 400
        ResponseEntity<String> noSource = rest.postForEntity("/api/failures",
                new FailureIntakeRequest(null, null, "pay-gw", "java.net.SocketTimeoutException", "t/o", null, null, "{}"),
                String.class);
        assertThat(noSource.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // valid without a correlation id -> server generates an http-… id and ingests it
        ResponseEntity<ActionAccepted> ok = rest.postForEntity("/api/failures",
                new FailureIntakeRequest(null, "payment-gateway", "pay-gw",
                        "java.net.SocketTimeoutException", "gateway timeout", null, null, "{}"),
                ActionAccepted.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ok.getBody()).isNotNull();
        String id = ok.getBody().target();
        assertThat(id).startsWith("http-");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(id)).isPresent());
    }
}
