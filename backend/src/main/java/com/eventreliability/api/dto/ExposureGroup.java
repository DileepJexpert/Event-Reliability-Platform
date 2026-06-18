package com.eventreliability.api.dto;

import java.util.Map;

/** Exposure for one grouping (a team or a topic): how many stuck failures, and the amount per currency. */
public record ExposureGroup(String name, long count, Map<String, Double> amountByCurrency) {
}
