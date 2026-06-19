package com.brod.starter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the starter's core promise: when a consumer exhausts its retries, the failed record is
 * captured to Brod's inbound DLQ with the header contract stamped — not silently dropped.
 */
@SpringBootTest(classes = StarterTestApp.class)
@EmbeddedKafka(partitions = 1, topics = {"app.input", "reliability.dlq.inbound"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.application.name=demo-app",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=starter-it",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "brod.dlq-topic=reliability.dlq.inbound",
        "brod.retries=1",
        "brod.backoff-ms=100"
})
class BrodStarterAutoDlqTest {

    @Autowired
    private KafkaTemplate<Object, Object> template;
    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void exhaustedRetryIsCapturedToBrodDlqWithContract() {
        template.send("app.input", "key-1", "boom-payload");

        Map<String, Object> props = KafkaTestUtils.consumerProps("dlq-reader", "true", broker);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (Consumer<String, String> consumer =
                     new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            consumer.subscribe(List.of("reliability.dlq.inbound"));
            ConsumerRecord<String, String> rec =
                    KafkaTestUtils.getSingleRecord(consumer, "reliability.dlq.inbound", Duration.ofSeconds(20));

            assertThat(rec.key()).isEqualTo("key-1");
            assertThat(rec.value()).isEqualTo("boom-payload"); // original payload preserved
            assertThat(header(rec, BrodHeaders.ORIGINAL_TOPIC)).isEqualTo("app.input");
            assertThat(header(rec, BrodHeaders.EXCEPTION_CLASS)).contains("IllegalStateException");
            assertThat(header(rec, BrodHeaders.EXCEPTION_MESSAGE)).contains("boom-payload");
            assertThat(header(rec, BrodHeaders.SOURCE_APP)).isEqualTo("demo-app");
            assertThat(header(rec, BrodHeaders.ATTEMPT_COUNT)).isEqualTo("1");
            assertThat(header(rec, BrodHeaders.CORRELATION_ID)).isNotBlank();
        }
    }

    private static String header(ConsumerRecord<?, ?> rec, String key) {
        Header h = rec.headers().lastHeader(key);
        return h == null || h.value() == null ? null : new String(h.value(), UTF_8);
    }
}
