package com.eventreliability.api.dto;

/**
 * Body for a maker's replay request (§13). All fields optional.
 *
 * @param reason        operator justification (audited)
 * @param targetTopic   destination override; must be the message's original topic or in
 *                      {@code reliability.replay.allowed-topics}. Null = original topic.
 * @param payloadBase64 corrected payload (base64) to send instead of the original. Null = unchanged.
 */
public record ReplayRequest(String reason, String targetTopic, String payloadBase64) {
}
