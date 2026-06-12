package com.eventreliability.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Per-window aggregate for pattern detection (§7.2): how many failures of a given root-cause
 * signature have landed in the current window, plus one example correlation id for quick drill-in.
 * The window's start/end come from the windowed key, so they aren't stored here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncidentAccumulator(long count, String example) {

    public static IncidentAccumulator empty() {
        return new IncidentAccumulator(0, null);
    }

    public IncidentAccumulator add(String correlationId) {
        String keepExample = example != null ? example
                : (correlationId == null || correlationId.isBlank() ? null : correlationId);
        return new IncidentAccumulator(count + 1, keepExample);
    }
}
