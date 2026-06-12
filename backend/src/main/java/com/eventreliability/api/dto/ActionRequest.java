package com.eventreliability.api.dto;

/** Optional body for a mutating action, carrying the operator's reason/justification (audited). */
public record ActionRequest(String reason) {
}
