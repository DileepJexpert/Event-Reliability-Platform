package com.eventreliability.api;

/** Uniform error body for the REST API. */
public record ApiError(long timestamp, int status, String error, String message, String path) {
}
