package com.eventreliability.api;

import java.util.Comparator;
import java.util.List;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.ReplayRequest;
import com.eventreliability.control.ApprovalService;
import com.eventreliability.domain.Incident;
import com.eventreliability.query.NotFoundException;
import com.eventreliability.security.CurrentUser;
import com.eventreliability.streams.ReadModels;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident endpoints (§15). Reads list/detail active incidents; the bulk-replay action (§13) is a
 * <em>maker</em> request — in maker-checker mode a different checker must approve it before the whole
 * cohort is re-driven — and is fully audited.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final ReadModels readModels;
    private final ApprovalService approvalService;

    public IncidentController(ReadModels readModels, ApprovalService approvalService) {
        this.readModels = readModels;
        this.approvalService = approvalService;
    }

    @GetMapping
    public List<Incident> list() {
        return readModels.allIncidents().stream()
                .sorted(Comparator.comparingLong(Incident::firstSeenAt).reversed())
                .toList();
    }

    @GetMapping("/{id}")
    public Incident detail(@PathVariable("id") String id) {
        return readModels.incident(id)
                .orElseThrow(() -> new NotFoundException("No incident found for id " + id));
    }

    /** {@code POST /api/incidents/{id}/bulk-replay} — maker requests one-click cohort recovery (§13). */
    @PostMapping("/{id}/bulk-replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted bulkReplay(@PathVariable("id") String id,
                                     @RequestBody(required = false) ReplayRequest request) {
        String actor = CurrentUser.name();
        String requestId = approvalService.requestBulkReplay(id, actor,
                request == null ? null : request.reason(),
                request == null ? null : request.targetTopic());
        return ActionAccepted.of("bulk-replay", id, actor, requestId);
    }
}
