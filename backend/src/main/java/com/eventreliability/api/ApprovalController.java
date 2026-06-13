package com.eventreliability.api;

import java.util.Comparator;
import java.util.List;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.ActionRequest;
import com.eventreliability.api.dto.ApprovalDto;
import com.eventreliability.api.dto.ReplayRequest;
import com.eventreliability.control.ApprovalService;
import com.eventreliability.domain.ControlRequest;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.query.NotFoundException;
import com.eventreliability.security.CurrentUser;
import com.eventreliability.state.StateService;
import com.eventreliability.streams.ReadModels;

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
 * Maker-checker approval queue (§13, §17). A checker (APPROVER) lists pending requests, inspects each
 * (including the original vs corrected payload for a diff) and approves or rejects it — and must be a
 * different user than the maker. Reads are open to VIEWER/OPERATOR/APPROVER; approve/reject are
 * APPROVER-only (enforced by the {@code secure} security chain).
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ReadModels readModels;
    private final ApprovalService approvalService;
    private final StateService stateService;

    public ApprovalController(ReadModels readModels, ApprovalService approvalService, StateService stateService) {
        this.readModels = readModels;
        this.approvalService = approvalService;
        this.stateService = stateService;
    }

    /**
     * {@code GET /api/approvals?status=PENDING|RETURNED|ALL} — the approval queue, oldest first.
     * Defaults to PENDING (the checker queue); RETURNED is the maker's correction queue.
     */
    @GetMapping
    public List<ApprovalDto> list(@RequestParam(required = false, defaultValue = "PENDING") String status) {
        final List<ControlRequest> source = "ALL".equalsIgnoreCase(status)
                ? readModels.allControlRequests()
                : readModels.requestsByStatus(parseStatus(status));
        return source.stream()
                .sorted(Comparator.comparingLong(ControlRequest::createdAt))
                .map(this::toDto)
                .toList();
    }

    private static ControlRequest.Status parseStatus(String status) {
        try {
            return ControlRequest.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown approval status '" + status + "'");
        }
    }

    /** {@code GET /api/approvals/{requestId}} — one request, with original vs corrected payload. */
    @GetMapping("/{requestId}")
    public ApprovalDto get(@PathVariable String requestId) {
        return readModels.controlRequest(requestId).map(this::toDto)
                .orElseThrow(() -> new NotFoundException("No approval request " + requestId));
    }

    /** {@code POST /api/approvals/{requestId}/approve} — checker approves (APPROVER, must differ from maker). */
    @PostMapping("/{requestId}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted approve(@PathVariable String requestId,
                                  @RequestBody(required = false) ActionRequest request) {
        String checker = CurrentUser.name();
        ControlRequest req = approvalService.approve(requestId, checker, request == null ? null : request.reason());
        return ActionAccepted.of("approve", req.target(), checker, requestId);
    }

    /** {@code POST /api/approvals/{requestId}/reject} — checker rejects (APPROVER, must differ from maker). */
    @PostMapping("/{requestId}/reject")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted reject(@PathVariable String requestId,
                                 @RequestBody(required = false) ActionRequest request) {
        String checker = CurrentUser.name();
        ControlRequest req = approvalService.reject(requestId, checker, request == null ? null : request.reason());
        return ActionAccepted.of("reject", req.target(), checker, requestId);
    }

    /**
     * {@code POST /api/approvals/{requestId}/return} — checker returns the request to the maker for
     * correction (APPROVER, ≠ maker), optionally suggesting a corrected payload / target topic.
     */
    @PostMapping("/{requestId}/return")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted returnToMaker(@PathVariable String requestId,
                                        @RequestBody(required = false) ReplayRequest request) {
        String checker = CurrentUser.name();
        ControlRequest req = approvalService.returnToMaker(requestId, checker,
                request == null ? null : request.reason(),
                request == null ? null : request.targetTopic(),
                request == null ? null : request.payloadBase64());
        return ActionAccepted.of("return", req.target(), checker, requestId);
    }

    /**
     * {@code POST /api/approvals/{requestId}/resubmit} — a maker (OPERATOR) corrects a returned
     * request and resubmits it for approval. The resubmitter becomes the maker; a checker must still
     * differ from them.
     */
    @PostMapping("/{requestId}/resubmit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted resubmit(@PathVariable String requestId,
                                   @RequestBody(required = false) ReplayRequest request) {
        String maker = CurrentUser.name();
        ControlRequest req = approvalService.resubmit(requestId, maker,
                request == null ? null : request.reason(),
                request == null ? null : request.targetTopic(),
                request == null ? null : request.payloadBase64());
        return ActionAccepted.of("resubmit", req.target(), maker, requestId);
    }

    private ApprovalDto toDto(ControlRequest r) {
        String originalPayload = null;
        String exceptionClass = null;
        String originalTopic = null;
        if (r.correlationId() != null) {
            FailureRecord rec = stateService.find(r.correlationId()).orElse(null);
            if (rec != null) {
                originalPayload = rec.payloadBase64();
                exceptionClass = rec.exceptionClass();
                originalTopic = rec.originalTopic();
            }
        }
        return new ApprovalDto(r.requestId(), r.type().name(), r.correlationId(), r.incidentId(),
                r.maker(), r.makerReason(), r.targetTopic(), r.payloadEdited(), r.payloadOverrideBase64(),
                originalPayload, exceptionClass, originalTopic, r.status().name(), r.createdAt(),
                r.checker(), r.checkerReason(), r.decidedAt(), r.revision());
    }
}
