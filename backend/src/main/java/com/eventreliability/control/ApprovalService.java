package com.eventreliability.control;

import java.util.Map;
import java.util.UUID;

import com.eventreliability.audit.AuditService;
import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.ControlCommand;
import com.eventreliability.domain.ControlRequest;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.Incident;
import com.eventreliability.domain.MessageState;
import com.eventreliability.query.NotFoundException;
import com.eventreliability.state.StateService;
import com.eventreliability.streams.ReadModels;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maker-checker control plane (§13, §17). Makers raise replay / bulk-replay / quarantine <em>requests</em>;
 * a different checker approves or rejects them (4-eyes / dual control). On approval an execution
 * {@link ControlCommand} is published to {@code reliability.control.commands} and run by exactly one
 * instance (the partition owner) via {@link ControlCommandListener}, keeping execution HA and ordered.
 * Requests live on the compacted {@code reliability.control.requests} topic — the pending-approvals
 * view. Every step (request, approve, reject, execute) is audited with the acting user.
 *
 * <p>With {@code reliability.replay.approval-required=false} the maker's action executes directly
 * (legacy/dev behaviour, for non-regulated deployments).
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ReliabilityProperties props;
    private final TopicNames topics;
    private final KafkaPublisher publisher;
    private final StateService stateService;
    private final AuditService auditService;
    private final ReadModels readModels;

    public ApprovalService(ReliabilityProperties props, TopicNames topics, KafkaPublisher publisher,
                           StateService stateService, AuditService auditService, ReadModels readModels) {
        this.props = props;
        this.topics = topics;
        this.publisher = publisher;
        this.stateService = stateService;
        this.auditService = auditService;
        this.readModels = readModels;
    }

    // ---------------- maker side ----------------

    /** Raise (or, in direct mode, execute) a single replay. Returns the requestId, or null in direct mode. */
    public String requestReplay(String correlationId, String maker, String reason,
                                String targetTopic, String payloadBase64) {
        FailureRecord record = requireFailure(correlationId);
        requireAllowedTarget(targetTopic, record.originalTopic());

        if (!props.replay().approvalRequired()) {
            stateService.put(record.toBuilder().state(MessageState.REPLAY_REQUESTED).lastActor(maker).build());
            auditService.userAction(correlationId, record.state(), MessageState.REPLAY_REQUESTED,
                    "REPLAY_REQUESTED", maker, reason != null ? reason : "single replay");
            publisher.sendJson(topics.controlCommands(), correlationId, new ControlCommand(
                    ControlCommand.Type.REPLAY, correlationId, null, maker, reason, null, targetTopic,
                    payloadBase64, System.currentTimeMillis()));
            return null;
        }

        ControlRequest req = newRequest(ControlCommand.Type.REPLAY, correlationId, null, targetTopic,
                payloadBase64, maker, reason, record.state());
        publisher.sendJson(topics.controlRequests(), req.requestId(), req);
        stateService.put(record.toBuilder().state(MessageState.REPLAY_REQUESTED).lastActor(maker).build());
        auditService.record(correlationId, record.state(), MessageState.REPLAY_REQUESTED, "REPLAY_REQUESTED",
                maker, makerNote("replay", req), Map.of("requestId", req.requestId()));
        log.info("Maker {} requested REPLAY of {} (request {})", maker, correlationId, req.requestId());
        return req.requestId();
    }

    public String requestQuarantine(String correlationId, String maker, String reason) {
        FailureRecord record = requireFailure(correlationId);
        if (!props.replay().approvalRequired()) {
            auditService.userAction(correlationId, record.state(), record.state(), "QUARANTINE_REQUESTED",
                    maker, reason != null ? reason : "manual quarantine");
            publisher.sendJson(topics.controlCommands(), correlationId,
                    ControlCommand.quarantine(correlationId, maker, reason));
            return null;
        }
        ControlRequest req = newRequest(ControlCommand.Type.QUARANTINE, correlationId, null, null, null,
                maker, reason, record.state());
        publisher.sendJson(topics.controlRequests(), req.requestId(), req);
        auditService.record(correlationId, record.state(), record.state(), "QUARANTINE_REQUESTED", maker,
                makerNote("quarantine", req), Map.of("requestId", req.requestId()));
        log.info("Maker {} requested QUARANTINE of {} (request {})", maker, correlationId, req.requestId());
        return req.requestId();
    }

    public String requestBulkReplay(String incidentId, String maker, String reason, String targetTopic) {
        Incident incident = readModels.incident(incidentId)
                .orElseThrow(() -> new NotFoundException("No incident found for id " + incidentId));
        requireAllowedTarget(targetTopic, incident.sourceTopic());

        if (!props.replay().approvalRequired()) {
            auditService.record(incidentId, null, MessageState.BULK_REPLAY_REQUESTED, "BULK_REPLAY_REQUESTED",
                    maker, reason != null ? reason : "bulk replay for " + incident.rootCause(),
                    Map.of("incidentId", incidentId));
            publisher.sendJson(topics.controlCommands(), incidentId, new ControlCommand(
                    ControlCommand.Type.BULK_REPLAY, null, incidentId, maker, reason, null, targetTopic, null,
                    System.currentTimeMillis()));
            return null;
        }
        ControlRequest req = newRequest(ControlCommand.Type.BULK_REPLAY, null, incidentId, targetTopic, null,
                maker, reason, null);
        publisher.sendJson(topics.controlRequests(), req.requestId(), req);
        auditService.record(incidentId, null, MessageState.BULK_REPLAY_REQUESTED, "BULK_REPLAY_REQUESTED",
                maker, makerNote("bulk-replay", req), Map.of("requestId", req.requestId(), "incidentId", incidentId));
        log.info("Maker {} requested BULK_REPLAY of incident {} (request {})", maker, incidentId, req.requestId());
        return req.requestId();
    }

    // ---------------- checker side ----------------

    public ControlRequest approve(String requestId, String checker, String reason) {
        ControlRequest req = requirePending(requestId);
        requireDistinctChecker(req, checker);

        ControlRequest approved = req.decided(ControlRequest.Status.APPROVED, checker, reason);
        publisher.sendJson(topics.controlRequests(), requestId, approved);

        if (approved.correlationId() != null && approved.type() == ControlCommand.Type.REPLAY) {
            stateService.find(approved.correlationId()).ifPresent(r ->
                    stateService.put(r.toBuilder().state(MessageState.REPLAY_APPROVED).lastActor(checker).build()));
        }
        auditService.record(keyOf(approved), null, approvedState(approved.type()), approvedAction(approved.type()),
                checker, decisionNote("approved", approved), approvalAttrs(approved));

        // Execution runs on the partition owner (HA, ordered) — exactly as a directly-issued command.
        publisher.sendJson(topics.controlCommands(), keyOf(approved), ControlCommand.fromApproved(approved, checker));
        log.info("Checker {} APPROVED request {} ({} {})", checker, requestId, approved.type(), keyOf(approved));
        return approved;
    }

    public ControlRequest reject(String requestId, String checker, String reason) {
        ControlRequest req = requirePending(requestId);
        requireDistinctChecker(req, checker);

        ControlRequest rejected = req.decided(ControlRequest.Status.REJECTED, checker, reason);
        publisher.sendJson(topics.controlRequests(), requestId, rejected);

        // Revert a single message to the state it was in before the (now rejected) request.
        if (rejected.correlationId() != null && rejected.priorState() != null) {
            stateService.find(rejected.correlationId()).ifPresent(r ->
                    stateService.put(r.toBuilder().state(rejected.priorState()).lastActor(checker).build()));
        }
        auditService.record(keyOf(rejected), null, rejected.priorState(), rejectedAction(rejected.type()),
                checker, decisionNote("rejected", rejected), approvalAttrs(rejected));
        log.info("Checker {} REJECTED request {} ({} {})", checker, requestId, rejected.type(), keyOf(rejected));
        return rejected;
    }

    /** Checker sends the request back to the maker for correction, optionally suggesting a fix (§13). */
    public ControlRequest returnToMaker(String requestId, String checker, String reason,
                                        String suggestedTopic, String suggestedPayloadBase64) {
        ControlRequest req = requirePending(requestId);
        requireDistinctChecker(req, checker);
        requireAllowedTarget(suggestedTopic, originalTopicOf(req));

        ControlRequest returned = req.returnedToMaker(checker, reason, suggestedTopic, suggestedPayloadBase64);
        publisher.sendJson(topics.controlRequests(), requestId, returned);
        auditService.record(keyOf(returned), null, null, returnedAction(returned.type()), checker,
                "returned to maker " + req.maker() + (reason != null ? ": " + reason : ""),
                approvalAttrs(returned));
        log.info("Checker {} RETURNED request {} to maker {}", checker, requestId, req.maker());
        return returned;
    }

    /** A maker corrects a returned request and resubmits it for approval — a fresh revision (§13). */
    public ControlRequest resubmit(String requestId, String maker, String reason,
                                   String targetTopic, String payloadBase64) {
        ControlRequest req = requireReturned(requestId);
        requireAllowedTarget(targetTopic, originalTopicOf(req));

        ControlRequest resubmitted = req.resubmittedBy(maker, reason, targetTopic, payloadBase64);
        publisher.sendJson(topics.controlRequests(), requestId, resubmitted);
        if (resubmitted.correlationId() != null) {
            stateService.find(resubmitted.correlationId()).ifPresent(r ->
                    stateService.put(r.toBuilder().state(MessageState.REPLAY_REQUESTED).lastActor(maker).build()));
        }
        auditService.record(keyOf(resubmitted), null, MessageState.REPLAY_REQUESTED,
                resubmittedAction(resubmitted.type()), maker,
                makerNote("resubmit", resubmitted) + " (revision " + resubmitted.revision() + ")",
                approvalAttrs(resubmitted));
        log.info("Maker {} RESUBMITTED request {} (revision {})", maker, requestId, resubmitted.revision());
        return resubmitted;
    }

    // ---------------- helpers ----------------

    private ControlRequest newRequest(ControlCommand.Type type, String correlationId, String incidentId,
                                      String targetTopic, String payloadBase64, String maker, String reason,
                                      MessageState priorState) {
        return new ControlRequest(UUID.randomUUID().toString(), type, correlationId, incidentId, targetTopic,
                payloadBase64, ControlRequest.Status.PENDING, maker, reason, System.currentTimeMillis(),
                null, null, null, priorState, null, 0);
    }

    private void requireAllowedTarget(String targetTopic, String originalTopic) {
        if (!props.replay().isTargetAllowed(targetTopic, originalTopic)) {
            throw new IllegalArgumentException("Target topic '" + targetTopic
                    + "' is not permitted (not the original topic and not in reliability.replay.allowed-topics)");
        }
    }

    private void requireDistinctChecker(ControlRequest req, String checker) {
        if (props.replay().requireDistinctChecker() && req.maker() != null && req.maker().equals(checker)) {
            throw new SelfApprovalException("Maker-checker violation: '" + checker
                    + "' cannot approve their own request (maker was '" + req.maker() + "')");
        }
    }

    private ControlRequest requirePending(String requestId) {
        ControlRequest req = readModels.controlRequest(requestId)
                .orElseThrow(() -> new NotFoundException("No approval request " + requestId));
        if (req.status() != ControlRequest.Status.PENDING) {
            throw new IllegalStateException("Request " + requestId + " is already " + req.status());
        }
        return req;
    }

    private ControlRequest requireReturned(String requestId) {
        ControlRequest req = readModels.controlRequest(requestId)
                .orElseThrow(() -> new NotFoundException("No approval request " + requestId));
        if (req.status() != ControlRequest.Status.RETURNED) {
            throw new IllegalStateException("Request " + requestId + " is " + req.status() + ", not RETURNED");
        }
        return req;
    }

    private String originalTopicOf(ControlRequest req) {
        if (req.correlationId() != null) {
            return stateService.find(req.correlationId()).map(FailureRecord::originalTopic).orElse(null);
        }
        if (req.incidentId() != null) {
            return readModels.incident(req.incidentId()).map(Incident::sourceTopic).orElse(null);
        }
        return null;
    }

    private FailureRecord requireFailure(String correlationId) {
        return stateService.find(correlationId)
                .orElseThrow(() -> new NotFoundException("No failure found for correlation id " + correlationId));
    }

    private static String keyOf(ControlRequest req) {
        return req.correlationId() != null ? req.correlationId() : req.incidentId();
    }

    private static String makerNote(String what, ControlRequest req) {
        StringBuilder sb = new StringBuilder(what).append(" requested by ").append(req.maker());
        if (req.targetTopic() != null && !req.targetTopic().isBlank()) {
            sb.append(" -> ").append(req.targetTopic());
        }
        if (req.payloadEdited()) {
            sb.append(" (payload edited)");
        }
        if (req.makerReason() != null && !req.makerReason().isBlank()) {
            sb.append(": ").append(req.makerReason());
        }
        return sb.toString();
    }

    private static String decisionNote(String decision, ControlRequest req) {
        StringBuilder sb = new StringBuilder(decision).append(" by ").append(req.checker())
                .append(" (request ").append(req.requestId()).append(", maker ").append(req.maker()).append(")");
        if (req.checkerReason() != null && !req.checkerReason().isBlank()) {
            sb.append(": ").append(req.checkerReason());
        }
        return sb.toString();
    }

    private static Map<String, String> approvalAttrs(ControlRequest req) {
        return Map.of("requestId", req.requestId(), "type", req.type().name(),
                "maker", req.maker() == null ? "" : req.maker());
    }

    private static String approvedAction(ControlCommand.Type t) {
        return switch (t) {
            case REPLAY -> "REPLAY_APPROVED";
            case BULK_REPLAY -> "BULK_REPLAY_APPROVED";
            case QUARANTINE -> "QUARANTINE_APPROVED";
        };
    }

    private static String rejectedAction(ControlCommand.Type t) {
        return switch (t) {
            case REPLAY -> "REPLAY_REJECTED";
            case BULK_REPLAY -> "BULK_REPLAY_REJECTED";
            case QUARANTINE -> "QUARANTINE_REJECTED";
        };
    }

    private static String returnedAction(ControlCommand.Type t) {
        return switch (t) {
            case REPLAY -> "REPLAY_RETURNED";
            case BULK_REPLAY -> "BULK_REPLAY_RETURNED";
            case QUARANTINE -> "QUARANTINE_RETURNED";
        };
    }

    private static String resubmittedAction(ControlCommand.Type t) {
        return switch (t) {
            case REPLAY -> "REPLAY_RESUBMITTED";
            case BULK_REPLAY -> "BULK_REPLAY_RESUBMITTED";
            case QUARANTINE -> "QUARANTINE_RESUBMITTED";
        };
    }

    private static MessageState approvedState(ControlCommand.Type t) {
        return switch (t) {
            case REPLAY -> MessageState.REPLAY_APPROVED;
            case BULK_REPLAY -> MessageState.BULK_REPLAY_APPROVED;
            case QUARANTINE -> null;
        };
    }
}
