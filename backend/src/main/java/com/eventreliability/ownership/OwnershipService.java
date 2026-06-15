package com.eventreliability.ownership;

import java.util.List;

import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.config.ReliabilityProperties.Ownership.Rule;
import com.eventreliability.domain.FailureRecord;

import org.springframework.stereotype.Service;

/**
 * Resolves the team that owns a failure (multi-team routing), by matching its source app / original
 * topic against the configured ownership rules. The first matching rule wins; unmatched failures fall
 * back to the configured default team. The resolved {@link Owner} carries the team and its optional
 * notification channel (used by per-team alerting).
 */
@Service
public class OwnershipService {

    private final ReliabilityProperties.Ownership cfg;

    public OwnershipService(ReliabilityProperties props) {
        this.cfg = props.ownership();
    }

    /** The owning team name for a failure (never null). */
    public String teamFor(FailureRecord record) {
        return record == null
                ? cfg.defaultTeam()
                : ownerFor(record.sourceApp(), record.originalTopic()).team();
    }

    /** The owner (team + channel) for a source app / topic pair (never null). */
    public Owner ownerFor(String sourceApp, String topic) {
        for (Rule rule : cfg.rules()) {
            if (matches(rule, sourceApp, topic)) {
                return new Owner(rule.team(), rule.channel());
            }
        }
        return new Owner(cfg.defaultTeam(), null);
    }

    public String defaultTeam() {
        return cfg.defaultTeam();
    }

    public List<Rule> rules() {
        return cfg.rules();
    }

    /** A rule matches when every condition it specifies matches (AND); at least one must be specified. */
    private static boolean matches(Rule rule, String sourceApp, String topic) {
        boolean specified = false;
        if (notBlank(rule.sourceApp())) {
            if (!rule.sourceApp().equalsIgnoreCase(trim(sourceApp))) {
                return false;
            }
            specified = true;
        }
        if (notBlank(rule.topic())) {
            if (!rule.topic().equalsIgnoreCase(trim(topic))) {
                return false;
            }
            specified = true;
        }
        if (notBlank(rule.topicPrefix())) {
            if (topic == null || !topic.startsWith(rule.topicPrefix())) {
                return false;
            }
            specified = true;
        }
        return specified;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** The owning team and its optional notification channel (e.g. a Slack/Teams webhook). */
    public record Owner(String team, String channel) {
    }
}
