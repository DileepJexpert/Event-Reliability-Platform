package com.eventreliability.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.eventreliability.domain.FailureHeaders;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly-typed, externalised configuration for the whole platform (prefix {@code reliability.*}).
 *
 * <p>Everything that the spec says must be configurable lives here: the topic-name prefix, retry
 * tiers (§10), the pattern-detection window/threshold (§7.2), the notifier (§14) and housekeeping
 * cadence. Classification rules are intentionally externalised separately (see
 * {@code classification} package and {@code classification-rules.yml}) so they can evolve without
 * touching this binding.
 */
@ConfigurationProperties(prefix = "reliability")
public record ReliabilityProperties(

        /** Prefix prepended to every platform topic name. Default {@code reliability.}. */
        @DefaultValue("reliability.") String topicPrefix,

        @DefaultValue Topics topics,
        @DefaultValue Retry retry,
        @DefaultValue Pattern pattern,
        @DefaultValue Notifier notifier,
        @DefaultValue Housekeeping housekeeping,
        @DefaultValue Replay replay,
        @DefaultValue Headers headers,
        @DefaultValue Ingest ingest,
        @DefaultValue Ownership ownership,
        @DefaultValue CircuitBreaker circuitBreaker
) {

    /**
     * Which DLQ topics Brod consumes (§8). Defaults to just {@code <prefix>dlq.inbound}; add more full
     * topic names here to let multiple teams/domains each have their own DLQ — Brod subscribes to,
     * provisions and pattern-detects across all of them, and records which DLQ each failure arrived on.
     */
    public record Ingest(
            @DefaultValue List<String> dlqTopics
    ) {
        public Ingest {
            dlqTopics = dlqTopics == null ? List.of()
                    : dlqTopics.stream()
                            .filter(t -> t != null && !t.isBlank())
                            .map(String::trim)
                            .toList();
        }
    }

    /**
     * Team ownership mapping (multi-team routing). Each failure is attributed to an owning team by
     * matching its source app and/or original topic against these rules, in order — the first matching
     * rule wins; unmatched failures fall back to the configured default team. The optional
     * {@code channel} (e.g. a Slack/Teams webhook) is used by per-team notifications and is never
     * exposed over the API. A rule matches when every condition it sets matches (so a rule with only
     * {@code sourceApp} owns all of that app's failures; one with {@code topicPrefix} owns a domain).
     */
    public record Ownership(
            @DefaultValue("Unassigned") String defaultTeam,
            @DefaultValue List<Rule> rules
    ) {
        public Ownership {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }

        /** One ownership rule: match by source app / exact topic / topic prefix -> owning team + channel. */
        public record Rule(String sourceApp, String topic, String topicPrefix, String team, String channel) {
        }
    }

    /**
     * Inbound header-contract mapping (§6.3). Lets an onboarding org point the platform at the
     * correlation-id header their apps already emit (e.g. {@code correlationId}) instead of forcing
     * every producer to add the platform's native {@code x-correlation-id}. The native header is
     * always retained as a final fallback alias, so configuring this only <em>adds</em> higher-priority
     * names — it never removes backward compatibility.
     */
    public record Headers(
            /** Header names searched, in priority order, for the inbound correlation id. */
            @DefaultValue("correlationId") List<String> correlationId
    ) {
        public Headers {
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            if (correlationId != null) {
                for (String name : correlationId) {
                    if (name != null && !name.isBlank()) {
                        ordered.add(name.trim());
                    }
                }
            }
            // The platform's native header is always honoured as a final fallback (§6.3).
            ordered.add(FailureHeaders.CORRELATION_ID);
            correlationId = List.copyOf(ordered);
        }
    }

    /** Topic provisioning defaults (auto-create is disabled on target clusters — §8/§18.5). */
    public record Topics(
            @DefaultValue("3") int partitions,
            @DefaultValue("1") short replicationFactor,
            /** Long retention for the append-only audit log (default 90 days). */
            @DefaultValue("7776000000") long auditRetentionMs
    ) {}

    /** Tiered retry configuration (§10). Each tier is its own topic to avoid head-of-line blocking. */
    public record Retry(
            @DefaultValue List<Tier> tiers,
            /**
             * Maximum single pause applied by the delay loop before it re-polls and re-checks
             * eligibility (§18.1). Long tier delays are reached by repeatedly pausing for at most
             * this window, so the consumer keeps polling and never risks {@code max.poll.interval}.
             */
            @DefaultValue("PT30S") Duration maxPause
    ) {
        public Retry {
            if (tiers == null || tiers.isEmpty()) {
                tiers = List.of(
                        new Tier("5s", Duration.ofSeconds(5)),
                        new Tier("1m", Duration.ofMinutes(1)),
                        new Tier("5m", Duration.ofMinutes(5)),
                        new Tier("30m", Duration.ofMinutes(30)));
            } else {
                tiers = List.copyOf(tiers);
            }
        }

        /** A single retry tier: a short name (used in the topic suffix) and a backoff delay. */
        public record Tier(String name, Duration delay) {}

        public List<String> tierNames() {
            List<String> names = new ArrayList<>(tiers.size());
            for (Tier t : tiers) {
                names.add(t.name());
            }
            return names;
        }
    }

    /** Pattern detection / incident thresholds (§7.2). */
    public record Pattern(
            /** Tumbling window over which identical signatures are counted. */
            @DefaultValue("PT5M") Duration window,
            /** Count within {@link #window} that promotes a signature to an incident. */
            @DefaultValue("500") long threshold,
            /** Grace period allowed for late-arriving records in a window. */
            @DefaultValue("PT1M") Duration grace
    ) {}

    /** Minimal in-process incident notifier (§14). No external paging platform. */
    public record Notifier(
            @DefaultValue("false") boolean enabled,
            /** {@code log} (default) or {@code webhook}. */
            @DefaultValue("log") String channel,
            String webhookUrl
    ) {}

    /** Housekeeping cadence. {@code @Scheduled} is allowed here, NOT for the retry clock (§10/§18.2). */
    public record Housekeeping(
            /** Sweep period in millis (kept as a plain long so {@code @Scheduled} can read it directly). */
            @DefaultValue("3600000") long staleSweepIntervalMs,
            /**
             * After a retry is re-driven the platform cannot observe success directly (onboarded apps
             * only adopt the failure-header contract, not a success callback). If a re-driven message
             * does not come back as a new failure within this grace, it is presumed RESOLVED (§6.2).
             */
            @DefaultValue("PT2M") Duration resolveGrace,
            /** Terminal-and-aged state records older than this are tombstoned (§9). */
            @DefaultValue("P30D") Duration terminalRetention
    ) {}

    /** Replay / remediation behaviour (§10, §13). */
    public record Replay(
            /**
             * Where due retries are republished. {@code original} (default) republishes to the
             * message's {@code x-original-topic}; otherwise the fixed topic named here is used as a
             * dedicated retry-input topic the owning consumer also listens to.
             */
            @DefaultValue("original") String destination,
            /** Max records replayed per bulk-replay command batch (back-pressure guard). */
            @DefaultValue("1000") int bulkBatchSize,
            /**
             * Require maker-checker (4-eyes) approval before a replay/bulk-replay/quarantine executes
             * (§13/§17). When true a maker raises a request that a different checker must approve;
             * when false the maker's action executes directly (legacy/dev behaviour).
             */
            @DefaultValue("true") boolean approvalRequired,
            /** The checker must be a different user than the maker (segregation of duties). */
            @DefaultValue("true") boolean requireDistinctChecker,
            /**
             * Topics an operator may target on replay, in addition to the message's original topic.
             * Empty means only the original topic is permitted — so corrected messages can only be
             * sent to sanctioned destinations.
             */
            @DefaultValue List<String> allowedTopics
    ) {
        public Replay {
            allowedTopics = allowedTopics == null ? List.of() : List.copyOf(allowedTopics);
        }

        /** Whether {@code topic} is a permitted replay destination (original topic is always allowed). */
        public boolean isTargetAllowed(String topic, String originalTopic) {
            if (topic == null || topic.isBlank() || topic.equals(originalTopic)) {
                return true;
            }
            return allowedTopics.contains(topic);
        }
    }

    /**
     * Circuit breaker for the platform's OWN message processing (§ resilience). If Brod's listeners
     * throw repeatedly within {@link #window}, consumption is paused and a CRITICAL alert is raised
     * rather than silently skipping records ("stop-on-error" safety valve). Each failing record first
     * gets {@link #retries} quick re-attempts ({@link #retryInterval} apart). Resume via the admin API.
     */
    public record CircuitBreaker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10") int failureThreshold,
            @DefaultValue("PT1M") Duration window,
            @DefaultValue("2") int retries,
            @DefaultValue("PT2S") Duration retryInterval
    ) {}
}
