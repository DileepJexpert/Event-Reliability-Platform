package com.eventreliability.observability;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.Incident;
import com.eventreliability.streams.ReadModels;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * In-process platform metrics via Micrometer (§14) — exposed through Spring Actuator
 * ({@code /actuator/prometheus}, {@code /actuator/metrics}); no external APM. Counters track flow
 * (ingested, classified by class, retries scheduled/re-driven, resolved, replays); gauges expose the
 * current backlog (tracked, parked, active incidents) by reading the GlobalKTables on scrape.
 */
@Component
public class PlatformMetrics {

    private final MeterRegistry registry;
    private final Counter ingested;
    private final Counter retryScheduled;
    private final Counter retryRedriven;
    private final Counter resolved;
    private final ConcurrentMap<String, Counter> classifiedByClass = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> replaysByType = new ConcurrentHashMap<>();

    public PlatformMetrics(MeterRegistry registry, ReadModels readModels) {
        this.registry = registry;
        this.ingested = Counter.builder("erp.failures.ingested")
                .description("Failures ingested from the inbound topic").register(registry);
        this.retryScheduled = Counter.builder("erp.retry.scheduled")
                .description("Retries scheduled onto a tier topic").register(registry);
        this.retryRedriven = Counter.builder("erp.retry.redriven")
                .description("Messages re-driven to their source topic").register(registry);
        this.resolved = Counter.builder("erp.failures.resolved")
                .description("Failures resolved (presumed-resolved after re-drive)").register(registry);

        Gauge.builder("erp.failures.tracked", readModels, rm -> rm.allFailures().size())
                .description("Failures currently tracked in state").register(registry);
        Gauge.builder("erp.failures.parked", readModels, rm -> rm.parked().size())
                .description("Failures parked awaiting human review").register(registry);
        Gauge.builder("erp.incidents.active", readModels,
                        rm -> rm.allIncidents().stream().filter(i -> Incident.ACTIVE.equals(i.status())).count())
                .description("Active systemic incidents").register(registry);
    }

    public void ingested() {
        ingested.increment();
    }

    public void classified(FailureClassification classification) {
        classifiedByClass.computeIfAbsent(classification.name(), name ->
                Counter.builder("erp.failures.classified").tag("classification", name).register(registry))
                .increment();
    }

    public void retryScheduled() {
        retryScheduled.increment();
    }

    public void retryRedriven() {
        retryRedriven.increment();
    }

    public void resolved() {
        resolved.increment();
    }

    public void replay(String type) {
        replaysByType.computeIfAbsent(type, t ->
                Counter.builder("erp.replays").tag("type", t).register(registry)).increment();
    }
}
