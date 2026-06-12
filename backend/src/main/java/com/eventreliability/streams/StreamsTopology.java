package com.eventreliability.streams;

import com.eventreliability.common.JsonSerde;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.AuditEvent;
import com.eventreliability.domain.AuditTimeline;
import com.eventreliability.domain.FailureRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Kafka Streams read-model topology (§9). Builds the GlobalKTables that let any instance answer
 * console queries from a full local copy of the data — no inter-instance RPC. The pattern-detection
 * and parked-view stages are added in {@link PatternDetectionTopology}; everything shares the single
 * application-wide {@link StreamsBuilder} that Spring injects, so they compose into one topology.
 */
@Configuration
public class StreamsTopology {

    /**
     * Loads the compacted {@code reliability.state} topic — the system of record — as a GlobalKTable.
     * This is the primary read model: it carries the current {@link FailureRecord} for every
     * correlation id and serves the failures list, filters and detail (§15) with full-local reads.
     */
    @Bean
    public GlobalKTable<String, FailureRecord> failuresGlobalTable(
            StreamsBuilder builder, ObjectMapper mapper, TopicNames topics) {
        Serde<String> keySerde = Serdes.String();
        Serde<FailureRecord> valueSerde = new JsonSerde<>(mapper, FailureRecord.class);
        return builder.globalTable(topics.state(),
                Consumed.with(keySerde, valueSerde),
                Materialized.<String, FailureRecord, KeyValueStore<Bytes, byte[]>>as(StoreNames.FAILURES)
                        .withKeySerde(keySerde).withValueSerde(valueSerde));
    }

    /**
     * Aggregates the append-only audit log per correlation id into an ordered {@link AuditTimeline},
     * republishes it to the compacted {@code reliability.views.audit} topic and loads that as a
     * GlobalKTable — so the failure-detail endpoint can return the full audit history locally (§15).
     */
    @Bean
    public GlobalKTable<String, AuditTimeline> auditTimelineGlobalTable(
            StreamsBuilder builder, ObjectMapper mapper, TopicNames topics) {
        Serde<String> keySerde = Serdes.String();
        Serde<AuditEvent> eventSerde = new JsonSerde<>(mapper, AuditEvent.class);
        Serde<AuditTimeline> timelineSerde = new JsonSerde<>(mapper, AuditTimeline.class);

        builder.stream(topics.audit(), Consumed.with(keySerde, eventSerde))
                .groupByKey(Grouped.with(keySerde, eventSerde))
                .aggregate(AuditTimeline::empty,
                        (key, event, timeline) -> timeline.add(event),
                        Materialized.<String, AuditTimeline, KeyValueStore<Bytes, byte[]>>as("audit-timeline-agg")
                                .withKeySerde(keySerde).withValueSerde(timelineSerde))
                .toStream()
                .to(topics.viewsAudit(), Produced.with(keySerde, timelineSerde));

        return builder.globalTable(topics.viewsAudit(),
                Consumed.with(keySerde, timelineSerde),
                Materialized.<String, AuditTimeline, KeyValueStore<Bytes, byte[]>>as(StoreNames.AUDIT_TIMELINE)
                        .withKeySerde(keySerde).withValueSerde(timelineSerde));
    }
}
