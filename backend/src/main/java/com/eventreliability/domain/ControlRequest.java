package com.eventreliability.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A maker-checker control request (§13, §17). A <em>maker</em> (OPERATOR) submits a replay /
 * bulk-replay / quarantine request; it is held {@link Status#PENDING} until a <em>different</em>
 * checker (APPROVER) approves or rejects it — 4-eyes / dual control, mandatory in a bank.
 *
 * <p>Stored compacted on {@code reliability.control.requests}, keyed by {@code requestId}, and loaded
 * as a GlobalKTable so the console can show a pending-approvals queue. A request may carry a corrected
 * payload ({@code payloadOverrideBase64}) and/or an overridden destination ({@code targetTopic}); the
 * approving checker sees both before it executes.
 *
 * @param requestId             unique id (state key)
 * @param type                  REPLAY / BULK_REPLAY / QUARANTINE
 * @param correlationId         target message (single-message requests)
 * @param incidentId            target cohort (BULK_REPLAY)
 * @param targetTopic           destination override; {@code null} = the message's original topic
 * @param payloadOverrideBase64 corrected payload (base64); {@code null} = keep the original payload
 * @param status                lifecycle of the request itself
 * @param maker                 user who raised the request
 * @param makerReason           the maker's justification (audited)
 * @param createdAt             epoch millis the request was raised
 * @param checker               user who approved/rejected (null while pending)
 * @param checkerReason         the checker's justification (audited)
 * @param decidedAt             epoch millis of the decision (null while pending)
 * @param priorState            the message's state before the request, so a reject can revert it
 * @param detail                free-text note (e.g. execution result)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ControlRequest(
        String requestId,
        ControlCommand.Type type,
        String correlationId,
        String incidentId,
        String targetTopic,
        String payloadOverrideBase64,
        Status status,
        String maker,
        String makerReason,
        long createdAt,
        String checker,
        String checkerReason,
        Long decidedAt,
        MessageState priorState,
        String detail) {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        EXECUTED,
        FAILED
    }

    /** Whether a corrected payload was supplied. */
    public boolean payloadEdited() {
        return payloadOverrideBase64 != null && !payloadOverrideBase64.isBlank();
    }

    /** The single-message or incident target id, whichever applies. */
    public String target() {
        return correlationId != null ? correlationId : incidentId;
    }

    public ControlRequest decided(Status newStatus, String checkerName, String checkerReasonText) {
        return new ControlRequest(requestId, type, correlationId, incidentId, targetTopic,
                payloadOverrideBase64, newStatus, maker, makerReason, createdAt, checkerName,
                checkerReasonText, System.currentTimeMillis(), priorState, detail);
    }

    public ControlRequest withStatus(Status newStatus, String detailText) {
        return new ControlRequest(requestId, type, correlationId, incidentId, targetTopic,
                payloadOverrideBase64, newStatus, maker, makerReason, createdAt, checker, checkerReason,
                decidedAt, priorState, detailText);
    }
}
