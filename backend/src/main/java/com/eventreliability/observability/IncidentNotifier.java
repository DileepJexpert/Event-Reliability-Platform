package com.eventreliability.observability;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.eventreliability.common.JsonCodec;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.domain.Incident;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Minimal in-process incident notifier (§14): consumes {@code reliability.incidents} on the shared
 * platform consumer group (so each incident is handled by exactly one instance) and fans new
 * incidents to a log line or an internal webhook. No external paging platform; richer on-call is
 * explicitly out of scope.
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
    private final RestClient restClient = RestClient.create();
    private final Set<String> notified = ConcurrentHashMap.newKeySet();

    public IncidentNotifier(JsonCodec json, ReliabilityProperties props) {
        this.json = json;
        this.props = props;
    }

    @KafkaListener(topics = "#{@topicNames.incidents()}", id = "incident-notifier")
    public void onIncident(ConsumerRecord<String, byte[]> record) {
        log.info("RECV <- topic={} key={} partition={} offset={}", record.topic(), record.key(),
                record.partition(), record.offset());
        Incident incident = json.fromBytes(record.value(), Incident.class);
        if (incident == null) {
            return;
        }
        if (notified.size() > MAX_TRACKED) {
            notified.clear();
        }
        if (!notified.add(incident.id())) {
            return; // already notified for this incident
        }
        notify(incident);
    }

    private void notify(Incident incident) {
        ReliabilityProperties.Notifier cfg = props.notifier();
        String summary = String.format(
                "INCIDENT %s — %d failures of '%s' on '%s' in the window starting %d",
                incident.id(), incident.count(), incident.rootCause(), incident.sourceTopic(),
                incident.windowStart());

        if (!cfg.enabled()) {
            log.warn("[notifier disabled] {}", summary);
            return;
        }
        if ("webhook".equalsIgnoreCase(cfg.channel()) && cfg.webhookUrl() != null && !cfg.webhookUrl().isBlank()) {
            try {
                restClient.post()
                        .uri(cfg.webhookUrl())
                        .header("Content-Type", "application/json")
                        .body(incident)
                        .retrieve()
                        .toBodilessEntity();
                log.info("Notified webhook of {}", incident.id());
            } catch (Exception ex) {
                log.error("Failed to notify webhook of {}: {}", incident.id(), ex.getMessage());
            }
        } else {
            log.warn("[ALERT] {}", summary);
        }
    }
}
