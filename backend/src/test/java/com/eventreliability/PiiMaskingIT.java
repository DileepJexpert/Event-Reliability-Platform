package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import com.eventreliability.api.dto.FailureDetailDto;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;
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
 * PII masking: the API returns payloads with sensitive data replaced by {@code [MASKED:<type>]}
 * tokens, while the underlying state topic retains the original (optionally encrypted) payload
 * for faithful replay.
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
        "reliability.payload-protection.masking-enabled=true",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-pii-${random.uuid}"
})
class PiiMaskingIT {

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;
    @Autowired
    private TopicNames topics;
    @Autowired
    private ReadModels readModels;
    @Autowired
    private TestRestTemplate rest;

    @Test
    void apiMasksPiiInPayload() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        String piiPayload = "{\"customer\":\"Jane Doe\","
                + "\"ssn\":\"123-45-6789\","
                + "\"email\":\"jane.doe@acme-bank.com\","
                + "\"card\":\"4111-1111-1111-1111\","
                + "\"iban\":\"GB29NWBK60161331926819\","
                + "\"note\":\"VIP customer\"}";

        String correlationId = "pii-" + UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add(FailureHeaders.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_TOPIC, "customer.events".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.EXCEPTION_CLASS, "com.bank.ValidationException".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ATTEMPT_COUNT, "1".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, correlationId,
                piiPayload.getBytes(StandardCharsets.UTF_8), headers));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(correlationId)).isPresent());

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(com.eventreliability.security.CurrentUser.ACTOR_HEADER, "alice");

        ResponseEntity<FailureDetailDto> detail = rest.exchange(
                "/api/failures/" + correlationId, HttpMethod.GET,
                new HttpEntity<>(httpHeaders), FailureDetailDto.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);

        String maskedB64 = detail.getBody().payloadBase64();
        String maskedText = new String(Base64.getDecoder().decode(maskedB64), StandardCharsets.UTF_8);

        assertThat(maskedText).contains("[MASKED:ssn]");
        assertThat(maskedText).contains("[MASKED:email]");
        assertThat(maskedText).contains("[MASKED:credit-card]");
        assertThat(maskedText).contains("[MASKED:iban]");

        assertThat(maskedText).doesNotContain("123-45-6789");
        assertThat(maskedText).doesNotContain("jane.doe@acme-bank.com");
        assertThat(maskedText).doesNotContain("4111-1111-1111-1111");
        assertThat(maskedText).doesNotContain("GB29NWBK60161331926819");

        assertThat(maskedText).contains("Jane Doe");
        assertThat(maskedText).contains("VIP customer");
    }

    @Test
    void nonPiiPayloadReturnedUnchanged() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        String safePayload = "{\"orderId\":\"ORD-42\",\"status\":\"FAILED\",\"reason\":\"insufficient stock\"}";
        String correlationId = "safe-" + UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add(FailureHeaders.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_TOPIC, "orders.events".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.EXCEPTION_CLASS, "com.bank.StockException".getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ATTEMPT_COUNT, "1".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, correlationId,
                safePayload.getBytes(StandardCharsets.UTF_8), headers));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(correlationId)).isPresent());

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(com.eventreliability.security.CurrentUser.ACTOR_HEADER, "alice");

        ResponseEntity<FailureDetailDto> detail = rest.exchange(
                "/api/failures/" + correlationId, HttpMethod.GET,
                new HttpEntity<>(httpHeaders), FailureDetailDto.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);

        String returnedText = new String(Base64.getDecoder().decode(detail.getBody().payloadBase64()),
                StandardCharsets.UTF_8);
        assertThat(returnedText).isEqualTo(safePayload);
    }
}
