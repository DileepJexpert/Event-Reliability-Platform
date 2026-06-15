package com.eventreliability.observability;

import com.eventreliability.api.FailureMapper;
import com.eventreliability.common.JsonCodec;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.Incident;
import com.eventreliability.ownership.OwnershipService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Bridges Kafka topics to the SSE live feed (§14). These listeners use a per-instance unique consumer
 * group ({@code ${random.uuid}}) rather than the shared platform group, so every instance reads the
 * full stream and can push it to its own SSE subscribers (a group-partitioned consumer would only
 * see a slice). The {@code failure} feed skips deserialization entirely when no one is watching.
 *
 * <p>Note: notifications/fan-out are handled separately by {@link IncidentNotifier} on the shared
 * group so an incident pages exactly once, not once per instance.
 */
@Component
public class LiveFeedListeners {

    private final JsonCodec json;
    private final SseService sseService;
    private final OwnershipService ownership;

    public LiveFeedListeners(JsonCodec json, SseService sseService, OwnershipService ownership) {
        this.json = json;
        this.sseService = sseService;
        this.ownership = ownership;
    }

    @KafkaListener(topics = "#{@topicNames.state()}", id = "sse-failures", groupId = "erp-sse-failures-${random.uuid}")
    public void onStateChange(ConsumerRecord<String, byte[]> record) {
        if (!sseService.hasSubscribers() || record.value() == null) {
            return;
        }
        FailureRecord failure = json.fromBytes(record.value(), FailureRecord.class);
        if (failure != null) {
            sseService.broadcast("failure", FailureMapper.toSummary(failure, ownership.teamFor(failure)));
        }
    }

    @KafkaListener(topics = "#{@topicNames.incidents()}", id = "sse-incidents", groupId = "erp-sse-incidents-${random.uuid}")
    public void onIncident(ConsumerRecord<String, byte[]> record) {
        if (!sseService.hasSubscribers() || record.value() == null) {
            return;
        }
        Incident incident = json.fromBytes(record.value(), Incident.class);
        if (incident != null) {
            sseService.broadcast("incident", incident);
        }
    }
}
