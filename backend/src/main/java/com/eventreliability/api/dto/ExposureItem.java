package com.eventreliability.api.dto;

/** A single high-value stuck failure, for the "biggest exposures" table (no raw payload). */
public record ExposureItem(
        String correlationId,
        double amount,
        String currency,
        String team,
        String topic,
        String state,
        Long firstFailedAt) {
}
