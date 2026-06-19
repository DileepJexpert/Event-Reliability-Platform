package com.eventreliability.query;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.eventreliability.common.JsonCodec;
import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.Expectation;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Durable store of declared reconciliation expectations. Declarations are published to the compacted
 * {@code reliability.views.expectations} topic (so the latest per key survives and the platform stays
 * database-free), and projected into an in-memory map by a per-instance listener on a unique consumer
 * group — so every instance holds the full set and can reconcile locally (the same approach the SSE
 * feed uses). The map is rebuilt from the compacted topic on restart.
 */
@Service
public class ExpectationStore {

    private final KafkaPublisher publisher;
    private final TopicNames topics;
    private final JsonCodec json;
    private final Map<String, Expectation> expectations = new ConcurrentHashMap<>();

    public ExpectationStore(KafkaPublisher publisher, TopicNames topics, JsonCodec json) {
        this.publisher = publisher;
        this.topics = topics;
        this.json = json;
    }

    /** Declare (or update) an expectation: persist to the compacted topic and reflect it locally. */
    public void declare(Expectation expectation) {
        expectations.put(expectation.key(), expectation);
        publisher.sendJson(topics.expectations(), expectation.key(), expectation);
    }

    /** Forget an expectation (tombstone on the compacted topic). */
    public void delete(String key) {
        expectations.remove(key);
        publisher.tombstone(topics.expectations(), key);
    }

    public List<Expectation> all() {
        return List.copyOf(expectations.values());
    }

    public Optional<Expectation> get(String key) {
        return Optional.ofNullable(expectations.get(key));
    }

    @KafkaListener(topics = "#{@topicNames.expectations()}", id = "expectations",
            groupId = "erp-expectations-${random.uuid}")
    void onChange(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null) {
            expectations.remove(record.key());
            return;
        }
        Expectation expectation = json.fromBytes(record.value(), Expectation.class);
        if (expectation != null && expectation.key() != null) {
            expectations.put(expectation.key(), expectation);
        }
    }
}
