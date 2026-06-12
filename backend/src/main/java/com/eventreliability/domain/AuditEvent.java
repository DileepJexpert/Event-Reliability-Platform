package com.eventreliability.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An immutable audit entry (§7.1 step 5, §8). Every lifecycle transition and every human/control
 * action appends one of these to the append-only {@code reliability.audit} topic. Keyed by
 * correlation id so a message's full timeline can be reconstructed by reading its key.
 *
 * @param correlationId the message this entry concerns (also the Kafka key)
 * @param at            epoch millis the action was recorded
 * @param fromState     previous lifecycle state (nullable for the first entry)
 * @param toState       new lifecycle state
 * @param action        short machine label, e.g. {@code INGESTED}, {@code CLASSIFIED},
 *                      {@code RETRY_SCHEDULED}, {@code REPLAY_APPROVED}
 * @param actor         the acting principal: {@code system} for automatic transitions, or the
 *                      authenticated user for control actions (§13, §17)
 * @param detail        free-form human-readable context (reason, tier, error…)
 * @param attributes    optional structured key/values (tier, signature, incidentId…)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditEvent(
        String correlationId,
        long at,
        MessageState fromState,
        MessageState toState,
        String action,
        String actor,
        String detail,
        Map<String, String> attributes) {

    public static final String SYSTEM_ACTOR = "system";

    public AuditEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static AuditEvent system(String correlationId, MessageState from, MessageState to,
                                    String action, String detail) {
        return new AuditEvent(correlationId, System.currentTimeMillis(), from, to, action,
                SYSTEM_ACTOR, detail, Map.of());
    }

    public static AuditEvent byUser(String correlationId, MessageState from, MessageState to,
                                    String action, String actor, String detail) {
        return new AuditEvent(correlationId, System.currentTimeMillis(), from, to, action,
                actor == null ? SYSTEM_ACTOR : actor, detail, Map.of());
    }
}
