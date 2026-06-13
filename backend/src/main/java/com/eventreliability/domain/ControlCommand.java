package com.eventreliability.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A control-plane execution command (§13) consumed by the backend and executed by exactly one
 * instance (the partition owner) via the control listener — keeping replay HA and ordered.
 *
 * <p>In maker-checker mode this command is published only once a checker has <em>approved</em> a
 * {@link ControlRequest} (so it carries the originating {@code requestId} and the checker as actor);
 * in direct mode (approval disabled) the maker's action publishes it straight away. It may carry a
 * corrected payload and/or an overridden target topic.
 *
 * @param type                  the command type
 * @param correlationId         target message for single-message commands (REPLAY / QUARANTINE)
 * @param incidentId            target cohort for BULK_REPLAY
 * @param actor                 user accountable for execution (the checker, or the maker in direct mode)
 * @param reason                justification (audited)
 * @param requestId             originating maker-checker request, or {@code null} in direct mode
 * @param targetTopic           destination override, or {@code null} for the original topic
 * @param payloadOverrideBase64 corrected payload (base64), or {@code null} to keep the original
 * @param issuedAt              epoch millis
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ControlCommand(
        Type type,
        String correlationId,
        String incidentId,
        String actor,
        String reason,
        String requestId,
        String targetTopic,
        String payloadOverrideBase64,
        long issuedAt) {

    public enum Type {
        REPLAY,
        BULK_REPLAY,
        QUARANTINE
    }

    public static ControlCommand replay(String correlationId, String actor, String reason) {
        return new ControlCommand(Type.REPLAY, correlationId, null, actor, reason, null, null, null,
                System.currentTimeMillis());
    }

    public static ControlCommand bulkReplay(String incidentId, String actor, String reason) {
        return new ControlCommand(Type.BULK_REPLAY, null, incidentId, actor, reason, null, null, null,
                System.currentTimeMillis());
    }

    public static ControlCommand quarantine(String correlationId, String actor, String reason) {
        return new ControlCommand(Type.QUARANTINE, correlationId, null, actor, reason, null, null, null,
                System.currentTimeMillis());
    }

    /** Execution command for a checker-approved maker request (maker-checker, §13). */
    public static ControlCommand fromApproved(ControlRequest request, String checker) {
        return new ControlCommand(request.type(), request.correlationId(), request.incidentId(), checker,
                request.checkerReason(), request.requestId(), request.targetTopic(),
                request.payloadOverrideBase64(), System.currentTimeMillis());
    }
}
