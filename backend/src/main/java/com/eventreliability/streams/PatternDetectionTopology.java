package com.eventreliability.streams;

import java.time.Duration;

import com.eventreliability.common.JsonSerde;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.FailureHeaders;
import com.eventreliability.domain.Incident;
import com.eventreliability.domain.IncidentAccumulator;
import com.eventreliability.domain.RootCauseSignature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Continuous, independent pattern detection (§7.2). Reads the same inbound failure stream, derives a
 * root-cause signature from each message's headers (no Schema Registry — drift is inferred by
 * symptom, §12), and counts identical signatures in tumbling time windows grouped by source topic.
 * When a signature crosses the configured threshold within a window it emits an {@link Incident}
 * signal carrying the count, root cause, source topic and window — so a systemic break (e.g. an
 * upstream schema change producing thousands of identical failures) is caught within minutes rather
 * than discovered a day later.
 *
 * <p>The incident is written keyed by a stable id (signature + window start) to the compacted
 * {@code reliability.views.incidents} view (re-emits collapse to one record per incident) and to the
 * {@code reliability.incidents} live feed (consumed by the notifier and the console stream). Because
 * every underlying message is held by correlation id, the cohort can later be bulk-replayed with one
 * click once the upstream is fixed (§13).
 */
@Configuration
public class PatternDetectionTopology {

    private static final String WINDOW_STORE = "incident-window-store";

    @Bean
    public KStream<String, Incident> patternDetection(
            StreamsBuilder builder, ObjectMapper mapper, TopicNames topics, ReliabilityProperties props) {

        Serde<String> str = Serdes.String();
        Serde<IncidentAccumulator> accSerde = new JsonSerde<>(mapper, IncidentAccumulator.class);
        Serde<Incident> incidentSerde = new JsonSerde<>(mapper, Incident.class);

        long threshold = props.pattern().threshold();
        Duration window = props.pattern().window();
        Duration grace = props.pattern().grace();

        KStream<String, String> hits = builder
                .stream(topics.inbound(), Consumed.with(str, Serdes.ByteArray()))
                .process(SignatureProcessor::new);

        KStream<String, Incident> incidents = hits
                .groupByKey(Grouped.with(str, str))
                .windowedBy(TimeWindows.ofSizeAndGrace(window, grace))
                .aggregate(IncidentAccumulator::empty,
                        (signature, correlationId, acc) -> acc.add(correlationId),
                        Materialized.<String, IncidentAccumulator, WindowStore<Bytes, byte[]>>as(WINDOW_STORE)
                                .withKeySerde(str).withValueSerde(accSerde))
                .toStream()
                .filter((windowedKey, acc) -> acc != null && acc.count() >= threshold)
                .map((windowedKey, acc) -> {
                    String signature = windowedKey.key();
                    long start = windowedKey.window().start();
                    long end = windowedKey.window().end();
                    String incidentId = signature + ":" + start;
                    Incident incident = new Incident(
                            incidentId, signature, RootCauseSignature.sourceTopicOf(signature),
                            acc.count(), threshold, start, end, System.currentTimeMillis(),
                            Incident.ACTIVE, acc.example());
                    return KeyValue.pair(incidentId, incident);
                });

        // Compacted read-model view (dedup re-emits by incident id) + live feed for notifier/console.
        incidents.to(topics.viewsIncidents(), Produced.with(str, incidentSerde));
        incidents.to(topics.incidents(), Produced.with(str, incidentSerde));
        return incidents;
    }

    /**
     * Re-keys each inbound failure to its root-cause signature, carrying the correlation id as the
     * value. Header access requires the Processor API (the DSL has no header access); {@link Record}
     * exposes the message headers directly.
     */
    static final class SignatureProcessor implements Processor<String, byte[], String, String> {

        private ProcessorContext<String, String> context;

        @Override
        public void init(ProcessorContext<String, String> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, byte[]> record) {
            Headers headers = record.headers();
            String correlationId = FailureHeaders.getString(headers, FailureHeaders.CORRELATION_ID);
            if (correlationId == null && record.key() != null) {
                correlationId = record.key();
            }
            String signature = RootCauseSignature.of(headers);
            context.forward(record.withKey(signature).withValue(correlationId == null ? "" : correlationId));
        }
    }
}
