package com.eventreliability.control;

/**
 * Thrown when a user tries to approve or reject their own maker-checker request — a 4-eyes / dual
 * control violation (§13, §17). Surfaced as HTTP 403.
 */
public class SelfApprovalException extends RuntimeException {

    public SelfApprovalException(String message) {
        super(message);
    }
}
