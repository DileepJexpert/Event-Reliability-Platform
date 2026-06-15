package com.eventreliability;

import java.time.Duration;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.FailureDetailDto;
import com.eventreliability.api.dto.FailureIntakeRequest;
import com.eventreliability.api.dto.OwnershipDto;
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
 * Verifies multi-team ownership: configured rules resolve a failure's owning team (by source app and
 * by topic prefix), the resolved team is returned on the failure detail, and the mapping is exposed
 * (without channels) via {@code GET /api/ownership}. Failures are seeded through the HTTP intake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "reliability.topics.partitions=1",
        "reliability.topics.replication-factor=1",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-ownership-${random.uuid}",
        "reliability.ownership.default-team=Unassigned",
        "reliability.ownership.rules[0].source-app=payments-service",
        "reliability.ownership.rules[0].team=Payments",
        "reliability.ownership.rules[0].channel=https://hooks.example/payments",
        "reliability.ownership.rules[1].topic-prefix=orders.",
        "reliability.ownership.rules[1].team=Orders"
})
class OwnershipIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ReadModels readModels;

    @Test
    void resolvesOwningTeamOnFailuresAndExposesMappingWithoutChannels() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        // The mapping is exposed for the console — with teams, but never the channels.
        OwnershipDto mapping = rest.getForObject("/api/ownership", OwnershipDto.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.defaultTeam()).isEqualTo("Unassigned");
        assertThat(mapping.rules()).extracting(OwnershipDto.TeamRule::team).contains("Payments", "Orders");

        // A failure from payments-service is owned by Payments (matched by source app).
        String paymentsId = submit("payments.events", "payments-service", "java.net.SocketTimeoutException");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(paymentsId)).isPresent());
        FailureDetailDto payments = rest.getForObject("/api/failures/" + paymentsId, FailureDetailDto.class);
        assertThat(payments.owningTeam()).isEqualTo("Payments");

        // An orders.* failure is owned by Orders (matched by topic prefix), regardless of the app.
        String ordersId = submit("orders.created", "some-other-app", "com.bank.MysteryException");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(ordersId)).isPresent());
        FailureDetailDto orders = rest.getForObject("/api/failures/" + ordersId, FailureDetailDto.class);
        assertThat(orders.owningTeam()).isEqualTo("Orders");

        // An unmatched failure falls back to the default team.
        String otherId = submit("crm.events", "crm-service", "com.bank.MysteryException");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModels.failure(otherId)).isPresent());
        FailureDetailDto other = rest.getForObject("/api/failures/" + otherId, FailureDetailDto.class);
        assertThat(other.owningTeam()).isEqualTo("Unassigned");
    }

    private String submit(String source, String app, String exceptionClass) {
        ActionAccepted accepted = rest.postForObject("/api/failures",
                new FailureIntakeRequest(null, source, app, exceptionClass, "boom", null, null, "{}"),
                ActionAccepted.class);
        return accepted.target();
    }
}
