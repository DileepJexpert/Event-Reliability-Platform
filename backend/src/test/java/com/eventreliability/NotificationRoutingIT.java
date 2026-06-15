package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.observability.NotificationSender;
import com.eventreliability.streams.ReadModels;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies per-team incident routing (§14): a cohort of identical failures on a Payments-owned topic
 * raises an incident, and the notifier dispatches it to the Payments team's channel — captured via a
 * test {@link NotificationSender} rather than calling a real webhook. Also confirms de-duplication
 * (one notification per incident id).
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {})
@Import(NotificationRoutingIT.CapturingConfig.class)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "reliability.topics.partitions=1",
        "reliability.topics.replication-factor=1",
        "reliability.pattern.threshold=3",
        "reliability.pattern.window=120s",
        "reliability.pattern.grace=2s",
        "reliability.notifier.enabled=true",
        "reliability.ownership.rules[0].topic-prefix=payments.",
        "reliability.ownership.rules[0].team=Payments",
        "reliability.ownership.rules[0].channel=https://hooks.example/payments",
        "spring.kafka.streams.state-dir=${java.io.tmpdir}/erp-streams-notify-${random.uuid}"
})
class NotificationRoutingIT {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    /** Records the notifications the notifier dispatches, so the test can assert routing without HTTP. */
    static class CapturingSender implements NotificationSender {
        final List<Notification> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(Notification n) {
            sent.add(n);
        }
    }

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;
    @Autowired
    private TopicNames topics;
    @Autowired
    private ReadModels readModels;
    @Autowired
    private CapturingSender sender;

    @Test
    void incidentIsRoutedToOwningTeamChannel() {
        await().atMost(Duration.ofSeconds(60)).until(readModels::ready);

        for (int i = 0; i < 4; i++) {
            send("com.bank.payments.SchemaDriftException", "payments.events");
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            NotificationSender.Notification n = sender.sent.stream()
                    .filter(x -> x.incident() != null && "payments.events".equals(x.incident().sourceTopic()))
                    .findFirst().orElse(null);
            assertThat(n).isNotNull();
            assertThat(n.team()).isEqualTo("Payments");
            assertThat(n.channel()).isEqualTo("https://hooks.example/payments");
            assertThat(n.summary()).contains("Payments");
        });

        // De-duplication: the same incident id is only dispatched once even as it re-emits.
        long forPayments = sender.sent.stream()
                .filter(x -> x.incident() != null && "payments.events".equals(x.incident().sourceTopic()))
                .map(x -> x.incident().id())
                .distinct().count();
        assertThat(sender.sent.stream()
                .filter(x -> x.incident() != null && "payments.events".equals(x.incident().sourceTopic()))
                .count()).isEqualTo(forPayments);
    }

    private void send(String exceptionClass, String originalTopic) {
        String id = "corr-" + UUID.randomUUID();
        RecordHeaders headers = new RecordHeaders();
        headers.add(FailureHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ORIGINAL_TOPIC, originalTopic.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.EXCEPTION_CLASS, exceptionClass.getBytes(StandardCharsets.UTF_8));
        headers.add(FailureHeaders.ATTEMPT_COUNT, "1".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(new ProducerRecord<>(topics.inbound(), null, id,
                "{\"v\":1}".getBytes(StandardCharsets.UTF_8), headers));
    }
}
