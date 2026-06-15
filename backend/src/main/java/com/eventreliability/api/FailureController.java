package com.eventreliability.api;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.ActionRequest;
import com.eventreliability.api.dto.FailureDetailDto;
import com.eventreliability.api.dto.FailureSummaryDto;
import com.eventreliability.api.dto.PageDto;
import com.eventreliability.api.dto.ReplayRequest;
import com.eventreliability.control.ApprovalService;
import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.MessageState;
import com.eventreliability.query.FailureQueryService;
import com.eventreliability.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Failure endpoints (§15). Reads are allowed for VIEWER and OPERATOR. The mutating replay/quarantine
 * actions are <em>maker</em> requests (OPERATOR-only via the {@code secure} chain): in maker-checker
 * mode they raise an approval request that a different checker must approve (§13, §17); each step is
 * audited with the acting user. They return 202 Accepted with the pending {@code requestId}.
 */
@RestController
@RequestMapping("/api/failures")
public class FailureController {

    private final FailureQueryService queryService;
    private final ApprovalService approvalService;

    public FailureController(FailureQueryService queryService, ApprovalService approvalService) {
        this.queryService = queryService;
        this.approvalService = approvalService;
    }

    /**
     * {@code GET /api/failures?status=&topic=&dlqTopic=&sourceApp=&classification=&page=&size=} —
     * list/filter. {@code topic} matches the source (original) topic, {@code dlqTopic} the DLQ the
     * failure arrived on (multi-team), and {@code sourceApp} the owning application — so a service
     * owner can self-serve "show my failures" by their DLQ / topic / app.
     */
    @GetMapping
    public PageDto<FailureSummaryDto> list(
            @RequestParam(required = false) MessageState status,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String dlqTopic,
            @RequestParam(required = false) String sourceApp,
            @RequestParam(required = false) FailureClassification classification,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return queryService.list(status, topic, dlqTopic, sourceApp, classification, page, size);
    }

    /** {@code GET /api/failures/{correlationId}} — detail incl. full audit timeline. */
    @GetMapping("/{correlationId}")
    public FailureDetailDto detail(@PathVariable String correlationId) {
        return queryService.detail(correlationId);
    }

    /**
     * {@code POST /api/failures/{correlationId}/replay} — maker raises a replay request (§13).
     * Body may override the target topic and/or supply a corrected payload.
     */
    @PostMapping("/{correlationId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted replay(@PathVariable String correlationId,
                                 @RequestBody(required = false) ReplayRequest request) {
        String actor = CurrentUser.name();
        String requestId = approvalService.requestReplay(correlationId, actor,
                request == null ? null : request.reason(),
                request == null ? null : request.targetTopic(),
                request == null ? null : request.payloadBase64());
        return ActionAccepted.of("replay", correlationId, actor, requestId);
    }

    /** {@code POST /api/failures/{correlationId}/quarantine} — maker raises a quarantine request (§13). */
    @PostMapping("/{correlationId}/quarantine")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted quarantine(@PathVariable String correlationId,
                                     @RequestBody(required = false) ActionRequest request) {
        String actor = CurrentUser.name();
        String requestId = approvalService.requestQuarantine(correlationId, actor,
                request == null ? null : request.reason());
        return ActionAccepted.of("quarantine", correlationId, actor, requestId);
    }
}
