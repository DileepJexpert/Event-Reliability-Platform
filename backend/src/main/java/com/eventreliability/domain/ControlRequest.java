package com.eventreliability.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A maker-checker control request (§13, §17). A <em>maker</em> (OPERATOR) submits a replay /
 * bulk-replay / quarantine request; it is held {@link Status#PENDING} until a <em>different</em>
 * checker (APPROVER) approves it — 4-eyes / dual control. Instead of approving or rejecting, the
 * checker may also <em>return</em> it to the maker for correction ({@link Status#RETURNED}, optionally
 * with a suggested fix); any maker can then correct and resubmit it ({@link #resubmittedBy}), bumping
 * the {@code revision}, until a checker finally approves.
 *
 * <p>Stored compacted on {@code reliability.control.requests}, keyed by {@code requestId}, and loaded
 * as a GlobalKTable so the console can show the pending / returned queues. A request may carry a
 * corrected payload ({@code payloadOverrideBase64}) and/or an overridden destination ({@code targetTopic}).
 *
 * @param requestId             unique id (state key)
 * @param type                  REPLAY / BULK_REPLAY / QUARANTINE
 * @param correlationId         target message (single-message requests)
 * @param incidentId            target cohort (BULK_REPLAY)
 * @param targetTopic           destination override; {@code null} = the message's original topic
 * @param payloadOverrideBase64 corrected payload (base64); {@code null} = keep the original payload
 * @param status                lifecycle of the request itself
 * @param maker                 current maker (who raised or last resubmitted it)
 * @param makerReason           the maker's justification (audited)
 * @param createdAt             epoch millis the request was first raised
 * @param checker               user who last decided (approved/rejected/returned); null while fresh
 * @param checkerReason         the checker's justification / return note (audited)
 * @param decidedAt             epoch millis of the last decision (null while never decided)
 * @param priorState            the message's state before the request, so a reject can revert it
 * @param detail                free-text note (e.g. execution result)
 * @param revision              number of maker resubmissions (0 = original)
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
        String detail,
        int revision) {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        RETURNED,
        EXECUTED,
        FAILED
    }

    /** Whether a corrected payload is currently attached. */
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
                checkerReasonText, System.currentTimeMillis(), priorState, detail, revision);
    }

    public ControlRequest withStatus(Status newStatus, String detailText) {
        return new ControlRequest(requestId, type, correlationId, incidentId, targetTopic,
                payloadOverrideBase64, newStatus, maker, makerReason, createdAt, checker, checkerReason,
                decidedAt, priorState, detailText, revision);
    }

    /**
     * Checker returns the request to the maker for correction, optionally suggesting a corrected
     * payload / target topic and leaving a note. Stays attributed to the same maker until resubmitted.
     */
    public ControlRequest returnedToMaker(String checkerName, String note,
                                          String suggestedTopic, String suggestedPayloadBase64) {
        return new ControlRequest(requestId, type, correlationId, incidentId, suggestedTopic,
                suggestedPayloadBase64, Status.RETURNED, maker, makerReason, createdAt, checkerName, note,
                System.currentTimeMillis(), priorState, detail, revision);
    }

    /**
     * A maker corrects a returned request and resubmits it for approval — a new revision, attributed
     * to the resubmitting maker, with the prior checker decision cleared so it re-enters the queue.
     */
    public ControlRequest resubmittedBy(String makerName, String reason,
                                        String newTargetTopic, String newPayloadBase64) {
        return new ControlRequest(requestId, type, correlationId, incidentId, newTargetTopic,
                newPayloadBase64, Status.PENDING, makerName, reason, createdAt, null, null, null,
                priorState, detail, revision + 1);
    }
}
