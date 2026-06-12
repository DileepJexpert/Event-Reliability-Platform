package com.eventreliability.api;

import com.eventreliability.api.dto.FailureDetailDto;
import com.eventreliability.api.dto.FailureSummaryDto;
import com.eventreliability.api.dto.PageDto;
import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.MessageState;
import com.eventreliability.query.FailureQueryService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read endpoints for failures (§15). Mutating endpoints (replay / quarantine) are added by the
 * control plane (Phase 6). In the {@code secure} profile, GET is allowed for VIEWER and OPERATOR.
 */
@RestController
@RequestMapping("/api/failures")
public class FailureController {

    private final FailureQueryService queryService;

    public FailureController(FailureQueryService queryService) {
        this.queryService = queryService;
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
}
