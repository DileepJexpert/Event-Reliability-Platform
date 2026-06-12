package com.eventreliability.api;

import java.util.Comparator;
import java.util.List;

import com.eventreliability.api.dto.ActionAccepted;
import com.eventreliability.api.dto.ActionRequest;
import com.eventreliability.control.ControlCommandService;
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
 * Incident endpoints (§15). Reads list/detail active incidents; the OPERATOR-only bulk-replay action
 * (§13) drives one-click recovery of a whole cohort once the upstream is fixed and is fully audited.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final ReadModels readModels;
    private final ControlCommandService controlCommandService;

    public IncidentController(ReadModels readModels, ControlCommandService controlCommandService) {
        this.readModels = readModels;
        this.controlCommandService = controlCommandService;
    }

    @GetMapping
    public List<Incident> list() {
        return readModels.allIncidents().stream()
                .sorted(Comparator.comparingLong(Incident::firstSeenAt).reversed())
                .toList();
    }

    @GetMapping("/{id}")
    public Incident detail(@PathVariable String id) {
        return readModels.incident(id)
                .orElseThrow(() -> new NotFoundException("No incident found for id " + id));
    }

    /** {@code POST /api/incidents/{id}/bulk-replay} — one-click bulk recovery (operator, audited). */
    @PostMapping("/{id}/bulk-replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted bulkReplay(@PathVariable String id,
                                     @RequestBody(required = false) ActionRequest request) {
        String actor = CurrentUser.name();
        controlCommandService.requestBulkReplay(id, actor, request == null ? null : request.reason());
        return ActionAccepted.of("bulk-replay", id, actor);
    }
}
