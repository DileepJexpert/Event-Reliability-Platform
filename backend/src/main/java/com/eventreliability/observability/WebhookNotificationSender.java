package com.eventreliability.observability;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Default {@link NotificationSender}: POSTs a Slack/Teams-compatible {@code {"text": …}} body to the
 * resolved channel (an incoming webhook URL) when one is set, otherwise logs the alert. This is the
 * only outbound alerting integration the platform makes — no external paging platform is run (§14).
 */
@Component
public class WebhookNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationSender.class);

    private final RestClient restClient = RestClient.create();

    @Override
    public void send(Notification n) {
        if (n.channel() == null || n.channel().isBlank()) {
            log.warn("[ALERT] {}", n.summary());
            return;
        }
        try {
            restClient.post()
                    .uri(n.channel())
                    .header("Content-Type", "application/json")
                    .body(Map.of("text", n.summary()))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Notified {} channel of incident {}", n.team(), n.incident().id());
        } catch (Exception ex) {
            log.error("Failed to notify {} channel of incident {}: {}",
                    n.team(), n.incident().id(), ex.getMessage());
        }
    }
}
