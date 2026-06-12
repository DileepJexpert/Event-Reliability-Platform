package com.eventreliability.api.dto;

/**
 * Response to an accepted mutating action. The action is executed asynchronously via the control
 * topic, so the API returns 202 Accepted with the target and the acting user.
 */
public record ActionAccepted(String status, String action, String target, String actor) {

    public static ActionAccepted of(String action, String target, String actor) {
        return new ActionAccepted("accepted", action, target, actor);
    }
}
