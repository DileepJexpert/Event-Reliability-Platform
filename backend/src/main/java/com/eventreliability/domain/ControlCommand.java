package com.eventreliability.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A control-plane command (§13). Console actions (replay / bulk-replay / quarantine) publish one of
 * these to {@code reliability.control.commands}; the backend consumes and executes it. Carrying the
 * acting user on the command means "who clicked bulk-replay" is as auditable as the failures
 * themselves (§17). Approval is modelled as the command itself being issued by an authorised
 * OPERATOR — the human approval gate (§13).
 *
 * @param type          the command type
 * @param correlationId target message for single-message commands (REPLAY / QUARANTINE)
 * @param incidentId    target cohort for BULK_REPLAY
 * @param actor         authenticated user who issued (and thereby approved) the command
 * @param reason        optional operator-supplied reason / justification
 * @param issuedAt      epoch millis
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ControlCommand(
        Type type,
        String correlationId,
        String incidentId,
        String actor,
        String reason,
        long issuedAt) {

    public enum Type {
        REPLAY,
        BULK_REPLAY,
        QUARANTINE
    }

    public static ControlCommand replay(String correlationId, String actor, String reason) {
        return new ControlCommand(Type.REPLAY, correlationId, null, actor, reason,
                System.currentTimeMillis());
    }

    public static ControlCommand bulkReplay(String incidentId, String actor, String reason) {
        return new ControlCommand(Type.BULK_REPLAY, null, incidentId, actor, reason,
                System.currentTimeMillis());
    }

    public static ControlCommand quarantine(String correlationId, String actor, String reason) {
        return new ControlCommand(Type.QUARANTINE, correlationId, null, actor, reason,
                System.currentTimeMillis());
    }
}
