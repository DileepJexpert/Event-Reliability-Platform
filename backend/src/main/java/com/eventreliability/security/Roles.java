package com.eventreliability.security;

/**
 * Role constants (§17). {@code VIEWER} is read-only; {@code OPERATOR} (the maker) may raise
 * replay/quarantine requests; {@code APPROVER} (the checker) may approve/reject them — the second
 * pair of eyes in the maker-checker flow (§13). Authorities are stored Spring-style with the
 * {@code ROLE_} prefix.
 */
public final class Roles {

    private Roles() {
    }

    public static final String VIEWER = "VIEWER";
    public static final String OPERATOR = "OPERATOR";
    public static final String APPROVER = "APPROVER";

    public static final String ROLE_VIEWER = "ROLE_" + VIEWER;
    public static final String ROLE_OPERATOR = "ROLE_" + OPERATOR;
    public static final String ROLE_APPROVER = "ROLE_" + APPROVER;
}
