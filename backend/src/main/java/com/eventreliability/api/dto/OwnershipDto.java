package com.eventreliability.api.dto;

import java.util.List;

/**
 * Ownership mapping as exposed to the console (§15): the default team and the topic/app → team rules.
 * Notification channels are intentionally omitted so webhook URLs are never returned over the API.
 */
public record OwnershipDto(String defaultTeam, List<TeamRule> rules) {

    /** One ownership rule without its channel: which source app / topic / prefix maps to which team. */
    public record TeamRule(String sourceApp, String topic, String topicPrefix, String team) {
    }
}
