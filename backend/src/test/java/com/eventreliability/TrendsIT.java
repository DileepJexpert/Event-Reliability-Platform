package com.eventreliability;

import java.time.Duration;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.FailureIntakeRequest;
import com.eventreliability.api.dto.TrendsDto;
import com.eventreliability.streams.ReadModels;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Seeds a handful of failures through the HTTP intake, then asserts the Trends endpoint aggregates
 * them: headline total, per-classification breakdown, top source topics/apps, and a 14-day daily
 * series whose counts add up to the failures created today.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "reliability.topics.partitions=1",
        "reliability.topics.replication-factor=1",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-trends-${random.uuid}"
})
class TrendsIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ReadModels readModels;

    @Test
    void trendsAggregateOverIngestedFailures() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        submit("payments.events", "payments-service", "java.net.SocketTimeoutException");
        submit("payments.events", "payments-service", "java.net.SocketTimeoutException");
        submit("orders.events", "orders-service", "com.bank.MysteryException");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.allFailures().size()).isGreaterThanOrEqualTo(3));

        TrendsDto t = rest.getForObject("/api/trends", TrendsDto.class);
        assertThat(t).isNotNull();
        assertThat(t.total()).isGreaterThanOrEqualTo(3);
        assertThat(t.byClassification()).isNotEmpty();
        assertThat(t.byState()).isNotEmpty();
        assertThat(t.topTopics()).extracting(TrendsDto.NameCount::name).contains("payments.events");
        assertThat(t.topSourceApps()).extracting(TrendsDto.NameCount::name).contains("payments-service");
        assertThat(t.resolutionRate()).isBetween(0.0, 1.0);

        assertThat(t.daily()).hasSize(14);
        long dailySum = t.daily().stream().mapToLong(TrendsDto.DailyCount::count).sum();
        assertThat(dailySum).isGreaterThanOrEqualTo(3);
    }

    private void submit(String source, String app, String exceptionClass) {
        FailureIntakeRequest req =
                new FailureIntakeRequest(null, source, app, exceptionClass, "boom", null, null, "{}");
        rest.postForEntity("/api/failures", req, ActionAccepted.class);
    }
}
