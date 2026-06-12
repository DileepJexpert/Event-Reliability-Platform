package com.eventreliability.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A systemic-failure incident (§7.2). Emitted when a root-cause signature crosses the configured
 * threshold within a detection window. Carries everything the console needs to show the banner and
 * the everything the control plane needs to drive a one-click bulk replay of the whole cohort.
 *
 * @param id             stable id derived from signature + window start (so re-emits are idempotent)
 * @param rootCause      the root-cause signature (exception class + source topic + drift hint)
 * @param sourceTopic    the original topic the failing cohort came from
 * @param count          number of failures observed in the window
 * @param threshold      the threshold that was crossed
 * @param windowStart    epoch millis — window start (the "a field disappeared at 14:03" moment)
 * @param windowEnd      epoch millis — window end
 * @param firstSeenAt    epoch millis the incident was first emitted
 * @param status         {@code ACTIVE} or {@code RESOLVED}
 * @param exampleCorrelationId one correlation id from the cohort, for quick drill-in
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Incident(
        String id,
        String rootCause,
        String sourceTopic,
        long count,
        long threshold,
        long windowStart,
        long windowEnd,
        long firstSeenAt,
        String status,
        String exampleCorrelationId) {

    public static final String ACTIVE = "ACTIVE";
    public static final String RESOLVED = "RESOLVED";
}
