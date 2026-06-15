package com.eventreliability.api.dto;

/**
 * HTTP intake payload for the "all-error handler" ({@code POST /api/failures}). Lets a non-Kafka
 * producer — a batch/EOD job, a REST integration, a file processor — report a failure so it is
 * triaged by the platform exactly like a Kafka DLQ failure.
 *
 * <p>{@code source} and {@code exceptionClass} are required; everything else is optional.
 * {@code correlationId} is generated server-side when blank. {@code payload} is the opaque body
 * (stored verbatim for triage/replay). Fields map onto the {@code x-*} header contract (§6.3):
 * {@code source -> x-original-topic}, {@code sourceApp -> x-source-app}, etc.
 */
public record FailureIntakeRequest(
        String correlationId,
        String source,
        String sourceApp,
        String exceptionClass,
        String exceptionMessage,
        String stacktrace,
        String schemaVersion,
        String payload) {
}
