package com.eventreliability.api.dto;

import java.util.List;

/**
 * Distinct filter values across all failures (§16), used to power the console's filter autocomplete:
 * the source topics, the DLQ topics a failure arrived on, and the owning apps currently present.
 */
public record FacetsDto(List<String> topics, List<String> dlqTopics, List<String> sourceApps) {
}
