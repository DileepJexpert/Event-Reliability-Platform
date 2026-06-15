package com.eventreliability.observability;

import com.eventreliability.domain.Incident;

/**
 * Delivers an incident notification to a channel (an incoming webhook) or logs it when no channel is
 * resolved. Abstracted so routing/dedup (in {@link IncidentNotifier}) is separated from delivery and
 * can be swapped in tests.
 */
public interface NotificationSender {

    void send(Notification notification);

    /** A resolved notification: the owning team, its channel (nullable), a summary, and the incident. */
    record Notification(String team, String channel, String summary, Incident incident) {
    }
}
