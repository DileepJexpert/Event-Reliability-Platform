package com.eventreliability.control;

import java.util.Map;

import com.eventreliability.audit.AuditService;
import com.eventreliability.common.KafkaPublisher;
import com.eventreliability.config.TopicNames;
import com.eventreliability.domain.ControlCommand;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.Incident;
import com.eventreliability.domain.MessageState;
import com.eventreliability.query.NotFoundException;
import com.eventreliability.state.StateService;
import com.eventreliability.streams.ReadModels;

import org.springframework.stereotype.Service;

/**
 * Producer side of the control plane (§13). Console actions call these methods; each one audits the
 * request (attributing the acting OPERATOR — the human approval gate, since only OPERATORs may issue
 * mutations, §17) and publishes a {@link ControlCommand} to {@code reliability.control.commands}. The
 * command is then executed asynchronously by exactly one instance (the partition owner) via
 * {@link ControlCommandListener}, keeping replay HA and ordered.
 */
@Service
public class ControlCommandService {

    private final KafkaPublisher publisher;
    private final TopicNames topics;
    private final StateService stateService;
    private final AuditService auditService;
    private final ReadModels readModels;

    public ControlCommandService(KafkaPublisher publisher, TopicNames topics, StateService stateService,
                                 AuditService auditService, ReadModels readModels) {
        this.publisher = publisher;
        this.topics = topics;
        this.stateService = stateService;
        this.auditService = auditService;
        this.readModels = readModels;
    }

    public void requestReplay(String correlationId, String actor, String reason) {
        FailureRecord record = requireFailure(correlationId);
        stateService.put(record.toBuilder().state(MessageState.REPLAY_REQUESTED).lastActor(actor).build());
        auditService.userAction(correlationId, record.state(), MessageState.REPLAY_REQUESTED,
                "REPLAY_REQUESTED", actor, reason != null ? reason : "single replay requested");
        publisher.sendJson(topics.controlCommands(), correlationId,
                ControlCommand.replay(correlationId, actor, reason));
    }

    public void requestQuarantine(String correlationId, String actor, String reason) {
        FailureRecord record = requireFailure(correlationId);
        auditService.userAction(correlationId, record.state(), record.state(),
                "QUARANTINE_REQUESTED", actor, reason != null ? reason : "manual quarantine requested");
        publisher.sendJson(topics.controlCommands(), correlationId,
                ControlCommand.quarantine(correlationId, actor, reason));
    }

    public void requestBulkReplay(String incidentId, String actor, String reason) {
        Incident incident = readModels.incident(incidentId)
                .orElseThrow(() -> new NotFoundException("No incident found for id " + incidentId));
        auditService.record(incidentId, null, MessageState.BULK_REPLAY_REQUESTED, "BULK_REPLAY_REQUESTED",
                actor, reason != null ? reason : "bulk replay requested for " + incident.rootCause(),
                Map.of("incidentId", incidentId, "rootCause", incident.rootCause()));
        publisher.sendJson(topics.controlCommands(), incidentId,
                ControlCommand.bulkReplay(incidentId, actor, reason));
    }

    private FailureRecord requireFailure(String correlationId) {
        return stateService.find(correlationId)
                .orElseThrow(() -> new NotFoundException("No failure found for correlation id " + correlationId));
    }
}
