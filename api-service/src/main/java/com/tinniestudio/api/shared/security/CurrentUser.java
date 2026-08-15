package com.tinniestudio.api.shared.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.UUID;

/**
 * Resolves the authenticated caller's id from the {@code UserDetails} principal Spring Security
 * injects via {@code @AuthenticationPrincipal} — {@link com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter}
 * sets the principal's username to the user/admin's raw UUID string, so this is the single place
 * that encodes that convention. Previously each controller reimplemented
 * {@code UUID.fromString(principal.getUsername())} inline (some with a null-check, most without),
 * which meant a future change to the encoding — or even just adding the null-check consistently —
 * had to be found and applied at every call site individually.
 */
public final class CurrentUser {

    private CurrentUser() {}

    /** @throws AuthenticationCredentialsNotFoundException if principal is null (unauthenticated). */
    public static UUID id(UserDetails principal) {
        if (principal == null) throw new AuthenticationCredentialsNotFoundException("No credentials");
        return UUID.fromString(principal.getUsername());
    }

    /** For optional-auth endpoints: null in, null out — never throws. */
    public static UUID idOrNull(UserDetails principal) {
        return principal != null ? id(principal) : null;
    }
}
