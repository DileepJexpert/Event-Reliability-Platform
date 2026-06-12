package com.eventreliability.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The full ordered audit history for one correlation id (§15 — failure detail "incl. full audit
 * timeline"). Built by aggregating the append-only {@code reliability.audit} topic per key in a
 * Kafka Streams topology and materialised to a compacted view loaded as a GlobalKTable, so any
 * instance can answer the detail query locally (§9).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditTimeline(List<AuditEvent> events) {

    public AuditTimeline {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static AuditTimeline empty() {
        return new AuditTimeline(List.of());
    }

    public AuditTimeline add(AuditEvent event) {
        List<AuditEvent> next = new ArrayList<>(events);
        next.add(event);
        next.sort(Comparator.comparingLong(AuditEvent::at));
        return new AuditTimeline(next);
    }
}
