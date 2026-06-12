package com.eventreliability.state;

import java.util.Optional;

import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.streams.ReadModels;

import org.springframework.stereotype.Service;

/**
 * Writes and reads the system of record — the compacted {@code reliability.state} topic (§9). Each
 * lifecycle transition produces a fresh immutable {@link FailureRecord} snapshot written under the
 * correlation-id key; compaction keeps the latest, so the topic <em>is</em> current state. Reads are
 * served from the {@link ReadModels} GlobalKTable, never by scanning the topic.
 */
@Service
public class StateService {

    private final KafkaPublisher publisher;
    private final TopicNames topics;
    private final ReadModels readModels;

    public StateService(KafkaPublisher publisher, TopicNames topics, ReadModels readModels) {
        this.publisher = publisher;
        this.topics = topics;
        this.readModels = readModels;
    }

    /** Upsert the current state snapshot for a correlation id, stamping created/updated times. */
    public void put(FailureRecord record) {
        long now = System.currentTimeMillis();
        FailureRecord toStore = record.toBuilder()
                .createdAt(record.createdAt() == null ? now : record.createdAt())
                .updatedAt(now)
                .build();
        publisher.sendJson(topics.state(), record.correlationId(), toStore);
    }

    public Optional<FailureRecord> find(String correlationId) {
        return readModels.failure(correlationId);
    }

    /** Tombstone a terminal-and-aged record so compaction forgets it (§9 lifecycle/TTL). */
    public void forget(String correlationId) {
        publisher.tombstone(topics.state(), correlationId);
    }
}
