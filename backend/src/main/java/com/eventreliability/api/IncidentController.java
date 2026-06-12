package com.eventreliability.api;

import java.util.Comparator;
import java.util.List;

import com.eventreliability.domain.Incident;
import com.eventreliability.query.NotFoundException;
import com.eventreliability.streams.ReadModels;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read endpoints for systemic-failure incidents (§15 — {@code GET /api/incidents}). The bulk-replay
 * mutating endpoint is added by the control plane (Phase 6). Backed by the incidents GlobalKTable,
 * which is empty until the pattern-detection topology (Phase 5) is in place.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final ReadModels readModels;

    public IncidentController(ReadModels readModels) {
        this.readModels = readModels;
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
}
