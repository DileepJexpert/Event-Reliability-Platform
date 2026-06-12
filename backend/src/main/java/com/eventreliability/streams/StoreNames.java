package com.eventreliability.streams;

/**
 * Names of the queryable Kafka Streams state stores backing the GlobalKTable read models (§9).
 * Centralised so the topology that builds them and the {@code ReadModels} that query them agree.
 */
public final class StoreNames {

    private StoreNames() {
    }

    /** GlobalKTable over the compacted {@code reliability.state} topic — every current failure. */
    public static final String FAILURES = "failures-store";

    /** GlobalKTable over {@code reliability.views.audit} — full audit timeline per correlation id. */
    public static final String AUDIT_TIMELINE = "audit-timeline-store";

    /** GlobalKTable over {@code reliability.views.incidents} — active systemic incidents. */
    public static final String INCIDENTS = "incidents-store";
}
