package com.eventreliability.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the acting principal for audit purposes (§13, §17). Every mutating action records who
 * performed it; in the OIDC-secured profile this is the JWT subject/preferred-username, in the local
 * profile it falls back to {@code anonymous}.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static final String ANONYMOUS = "anonymous";

    public static String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ANONYMOUS;
        }
        String name = auth.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return ANONYMOUS;
        }
        return name;
    }
}
