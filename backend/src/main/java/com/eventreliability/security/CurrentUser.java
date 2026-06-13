package com.eventreliability.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the acting principal for audit and maker-checker purposes (§13, §17). In the OIDC-secured
 * profile this is the JWT subject/preferred-username — the real, trusted identity.
 *
 * <p>In the local/dev profile there is no IdP, so to let the 4-eyes flow be exercised with two
 * distinct users an {@code X-Actor} request header is honoured (e.g. {@code X-Actor: alice} for the
 * maker, {@code X-Actor: bob} for the checker). The header is ignored whenever a real authenticated
 * identity is present, so it can never be used to spoof a user in production.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static final String ANONYMOUS = "anonymous";

    /** Dev-only header to act as a specific user (maker-checker testing without an IdP). */
    public static final String ACTOR_HEADER = "X-Actor";

    public static String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            String name = auth.getName();
            if (name != null && !name.isBlank() && !"anonymousUser".equals(name)) {
                return name; // real (OIDC) identity always wins
            }
        }
        // No real identity (local/dev): allow X-Actor so maker and checker can be distinct.
        String header = headerActor();
        return header != null ? header : ANONYMOUS;
    }

    private static String headerActor() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            String value = servletAttrs.getRequest().getHeader(ACTOR_HEADER);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
