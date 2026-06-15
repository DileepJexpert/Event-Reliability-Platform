package com.eventreliability.observability;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.eventreliability.common.JsonCodec;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.domain.Incident;
import com.eventreliability.ownership.OwnershipService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Minimal in-process incident notifier (§14): consumes {@code reliability.incidents} on the shared
 * platform consumer group (so each incident is handled by exactly one instance) and routes new
 * incidents to the <em>owning team's</em> channel (multi-team), falling back to the global webhook or
 * a log line. No external paging platform; richer on-call is explicitly out of scope.
 *
 * <p>Incidents re-emit as their window count grows, so a first-seen set de-duplicates: each incident
 * id notifies once. The set is bounded with a coarse cap to avoid unbounded growth.
 */
@Component
public class IncidentNotifier {

    private static final Logger log = LoggerFactory.getLogger(IncidentNotifier.class);
    private static final int MAX_TRACKED = 50_000;

    private final JsonCodec json;
    private final ReliabilityProperties props;
    private final OwnershipService ownership;
    private final NotificationSender sender;
    private final Set<String> notified = ConcurrentHashMap.newKeySet();

    public IncidentNotifier(JsonCodec json, ReliabilityProperties props, OwnershipService ownership,
                            NotificationSender sender) {
        this.json = json;
        this.props = props;
        this.ownership = ownership;
        this.sender = sender;
    }

    @KafkaListener(topics = "#{@topicNames.incidents()}", id = "incident-notifier")
    public void onIncident(ConsumerRecord<String, byte[]> record) {
        log.info("RECV <- topic={} key={} partition={} offset={}", record.topic(), record.key(),
                record.partition(), record.offset());
        Incident incident = json.fromBytes(record.value(), Incident.class);
        if (incident != null) {
            handle(incident);
        }
    }

    /** De-duplicate, resolve the owning team + channel, and dispatch the notification. */
    void handle(Incident incident) {
        if (notified.size() > MAX_TRACKED) {
            notified.clear();
        }
        if (!notified.add(incident.id())) {
            return; // already notified for this incident
        }
        ReliabilityProperties.Notifier cfg = props.notifier();
        OwnershipService.Owner owner = ownership.ownerFor(null, incident.sourceTopic());
        String summary = String.format(
                "INCIDENT %s — %d failures of '%s' on '%s' (owner: %s) in the window starting %d",
                incident.id(), incident.count(), incident.rootCause(), incident.sourceTopic(),
                owner.team(), incident.windowStart());

        if (!cfg.enabled()) {
            log.warn("[notifier disabled] {}", summary);
            return;
        }
        // Prefer the owning team's channel; fall back to the global webhook (when in webhook mode).
        String globalWebhook = "webhook".equalsIgnoreCase(cfg.channel()) ? cfg.webhookUrl() : null;
        String channel = firstNonBlank(owner.channel(), globalWebhook);
        sender.send(new NotificationSender.Notification(owner.team(), channel, summary, incident));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }
}
