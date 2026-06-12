package com.eventreliability.security;

/**
 * Role constants (§17). {@code VIEWER} is read-only; {@code OPERATOR} can replay/quarantine.
 * Authorities are stored Spring-style with the {@code ROLE_} prefix.
 */
public final class Roles {

    private Roles() {
    }

    public static final String VIEWER = "VIEWER";
    public static final String OPERATOR = "OPERATOR";

    public static final String ROLE_VIEWER = "ROLE_" + VIEWER;
    public static final String ROLE_OPERATOR = "ROLE_" + OPERATOR;
}
