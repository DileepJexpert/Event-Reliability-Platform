package com.eventreliability.api.dto;

/**
 * Response to an accepted mutating action. Actions execute asynchronously via the control topic, so
 * the API returns 202 Accepted with the target and the acting user. In maker-checker mode a replay /
 * bulk-replay / quarantine returns the {@code requestId} of the pending approval (null in direct mode
 * or for an approve/reject acknowledgement).
 */
public record ActionAccepted(String status, String action, String target, String actor, String requestId) {

    public static ActionAccepted of(String action, String target, String actor) {
        return new ActionAccepted("accepted", action, target, actor, null);
    }

    public static ActionAccepted of(String action, String target, String actor, String requestId) {
        return new ActionAccepted("accepted", action, target, actor, requestId);
    }
}
