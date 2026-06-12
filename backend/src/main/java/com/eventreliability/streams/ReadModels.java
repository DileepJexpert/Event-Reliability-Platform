package com.eventreliability.streams;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eventreliability.domain.AuditTimeline;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.Incident;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Service;

/**
 * Read-only access to the GlobalKTable-backed state stores (§9). Because the stores are global, any
 * instance holds a full local copy and can answer any console query without inter-instance RPC.
 *
 * <p>All accessors degrade gracefully while the Streams runtime is still starting or rebalancing
 * (stores not yet queryable): single-key lookups return empty and listings return an empty list,
 * rather than throwing. Callers/UI can treat that as "read model warming up".
 */
@Service
public class ReadModels {

    private final StreamsBuilderFactoryBean streamsFactory;

    public ReadModels(StreamsBuilderFactoryBean streamsFactory) {
        this.streamsFactory = streamsFactory;
    }

    /** Whether the Streams runtime is up and its global stores are queryable. */
    public boolean ready() {
        KafkaStreams ks = streamsFactory.getKafkaStreams();
        return ks != null && ks.state() == KafkaStreams.State.RUNNING;
    }

    public Optional<FailureRecord> failure(String correlationId) {
        return failuresStore().map(s -> s.get(correlationId));
    }

    public List<FailureRecord> allFailures() {
        return failuresStore().map(this::drain).orElseGet(List::of);
    }

    public AuditTimeline auditTimeline(String correlationId) {
        return auditStore().map(s -> s.get(correlationId)).orElse(AuditTimeline.empty());
    }

    public Optional<Incident> incident(String id) {
        return incidentsStore().map(s -> s.get(id));
    }

    public List<Incident> allIncidents() {
        return incidentsStore().map(this::drain).orElseGet(List::of);
    }

    public List<FailureRecord> parked() {
        return parkedStore().map(this::drain).orElseGet(List::of);
    }

    // ----- typed store accessors -----

    private Optional<ReadOnlyKeyValueStore<String, FailureRecord>> failuresStore() {
        return store(StoreNames.FAILURES);
    }

    private Optional<ReadOnlyKeyValueStore<String, AuditTimeline>> auditStore() {
        return store(StoreNames.AUDIT_TIMELINE);
    }

    private Optional<ReadOnlyKeyValueStore<String, Incident>> incidentsStore() {
        return store(StoreNames.INCIDENTS);
    }

    private Optional<ReadOnlyKeyValueStore<String, FailureRecord>> parkedStore() {
        return store(StoreNames.PARKED);
    }

    private <V> Optional<ReadOnlyKeyValueStore<String, V>> store(String name) {
        KafkaStreams ks = streamsFactory.getKafkaStreams();
        if (ks == null || ks.state() != KafkaStreams.State.RUNNING) {
            return Optional.empty();
        }
        try {
            return Optional.of(ks.store(
                    StoreQueryParameters.fromNameAndType(name, QueryableStoreTypes.keyValueStore())));
        } catch (InvalidStateStoreException notReady) {
            return Optional.empty();
        }
    }

    private <V> List<V> drain(ReadOnlyKeyValueStore<String, V> store) {
        List<V> out = new ArrayList<>();
        try (KeyValueIterator<String, V> it = store.all()) {
            while (it.hasNext()) {
                out.add(it.next().value);
            }
        }
        return out;
    }
}
