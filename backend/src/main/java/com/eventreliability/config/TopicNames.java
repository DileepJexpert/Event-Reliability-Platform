package com.eventreliability.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Single source of truth for resolving fully-qualified Kafka topic names from the configurable
 * prefix (§8). Everything that names a topic should go through this component rather than
 * hard-coding strings, so the prefix can be changed in one place.
 */
@Component
public class TopicNames {

    private final String prefix;
    private final List<String> retryTierNames;

    public TopicNames(ReliabilityProperties props) {
        this.prefix = props.topicPrefix();
        this.retryTierNames = props.retry().tierNames();
    }

    private String n(String suffix) {
        return prefix + suffix;
    }

    /** Entry point for all failures (§8). */
    public String inbound() {
        return n("failures.inbound");
    }

    /**
     * Internal, asynchronous classification topic (§11). Classification is kept off the ingest
     * consume loop so a future heavy/LLM classifier can't throttle ingestion throughput.
     */
    public String classify() {
        return n("internal.classify");
    }

    /** One retry topic per tier (§10), e.g. {@code reliability.retry.5s}. */
    public String retry(String tierName) {
        return n("retry." + tierName);
    }

    /** All configured retry-tier topics — used by the delay-loop listener's subscription. */
    public String[] allRetryTopics() {
        return retryTierNames.stream().map(this::retry).toArray(String[]::new);
    }

    /** Terminal park / DLT for human review (§8) — exhausted retries and UNKNOWN messages. */
    public String parked() {
        return n("parked");
    }

    /** Business failures routed to their owner with a reason (§8). */
    public String businessRouted() {
        return n("business.routed");
    }

    /** Compacted system-of-record for each message's lifecycle, keyed by correlation id (§9). */
    public String state() {
        return n("state");
    }

    /** Append-only, long-retention immutable audit log (§8). */
    public String audit() {
        return n("audit");
    }

    /** Compacted materialised view of active incidents, loaded as a GlobalKTable (§9). */
    public String viewsIncidents() {
        return n("views.incidents");
    }

    /**
     * Compacted materialised view of the per-correlation-id audit timeline, loaded as a GlobalKTable
     * so the failure-detail endpoint can return the full audit history with a full-local read (§9, §15).
     */
    public String viewsAudit() {
        return n("views.audit");
    }

    /** Control plane: replay / bulk-replay / quarantine commands with acting user (§13). */
    public String controlCommands() {
        return n("control.commands");
    }

    /** Maker-checker control requests awaiting/after approval (§13), compacted by request id. */
    public String controlRequests() {
        return n("control.requests");
    }

    /** Incident feed for the notifier and the live console stream (§8). */
    public String incidents() {
        return n("incidents");
    }

    public String prefix() {
        return prefix;
    }

    /**
     * All non-retry, statically-named platform topics keyed by a human label. Used by the
     * provisioner and for diagnostics. Retry-tier topics are added by the provisioner from config.
     */
    public Map<String, String> staticTopics() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("inbound", inbound());
        m.put("classify", classify());
        m.put("parked", parked());
        m.put("businessRouted", businessRouted());
        m.put("state", state());
        m.put("audit", audit());
        m.put("viewsIncidents", viewsIncidents());
        m.put("viewsAudit", viewsAudit());
        m.put("controlCommands", controlCommands());
        m.put("controlRequests", controlRequests());
        m.put("incidents", incidents());
        return m;
    }
}
