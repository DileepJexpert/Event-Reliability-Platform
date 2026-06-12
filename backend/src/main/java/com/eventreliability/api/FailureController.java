package com.eventreliability.api;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.ActionRequest;
import com.eventreliability.api.dto.FailureDetailDto;
import com.eventreliability.api.dto.FailureSummaryDto;
import com.eventreliability.api.dto.PageDto;
import com.eventreliability.control.ControlCommandService;
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
 * Failure endpoints (§15). Reads are allowed for VIEWER and OPERATOR; the mutating replay/quarantine
 * actions are OPERATOR-only (enforced by the {@code secure} security chain) and each writes an audit
 * event attributing the acting user (§17). Mutations are executed asynchronously via the control
 * topic, so they return 202 Accepted.
 */
@RestController
@RequestMapping("/api/failures")
public class FailureController {

    private final FailureQueryService queryService;
    private final ControlCommandService controlCommandService;

    public FailureController(FailureQueryService queryService, ControlCommandService controlCommandService) {
        this.queryService = queryService;
        this.controlCommandService = controlCommandService;
    }

    /** {@code GET /api/failures?status=&topic=&classification=&page=&size=} — list/filter. */
    @GetMapping
    public PageDto<FailureSummaryDto> list(
            @RequestParam(required = false) MessageState status,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) FailureClassification classification,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return queryService.list(status, topic, classification, page, size);
    }

    /** {@code GET /api/failures/{correlationId}} — detail incl. full audit timeline. */
    @GetMapping("/{correlationId}")
    public FailureDetailDto detail(@PathVariable String correlationId) {
        return queryService.detail(correlationId);
    }

    /** {@code POST /api/failures/{correlationId}/replay} — single replay (operator, audited). */
    @PostMapping("/{correlationId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted replay(@PathVariable String correlationId,
                                 @RequestBody(required = false) ActionRequest request) {
        String actor = CurrentUser.name();
        controlCommandService.requestReplay(correlationId, actor, reasonOf(request));
        return ActionAccepted.of("replay", correlationId, actor);
    }

    /** {@code POST /api/failures/{correlationId}/quarantine} — manual quarantine (operator, audited). */
    @PostMapping("/{correlationId}/quarantine")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted quarantine(@PathVariable String correlationId,
                                     @RequestBody(required = false) ActionRequest request) {
        String actor = CurrentUser.name();
        controlCommandService.requestQuarantine(correlationId, actor, reasonOf(request));
        return ActionAccepted.of("quarantine", correlationId, actor);
    }

    private static String reasonOf(ActionRequest request) {
        return request == null ? null : request.reason();
    }
}
