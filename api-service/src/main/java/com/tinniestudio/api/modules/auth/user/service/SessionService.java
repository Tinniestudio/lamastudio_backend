package com.tinniestudio.api.modules.auth.user.service;

import com.tinniestudio.api.modules.auth.user.dto.SessionDto;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.UUID;

public interface SessionService {

    UUID createSession(UUID userId, String rawRefreshToken, HttpServletRequest request);

    void validateSession(UUID userId, UUID sessionId);

    /**
     * Fast, side-effect-free check of whether a session is still active (not revoked, exists).
     * Used on every authenticated request to reject access tokens whose session was revoked
     * (logout on another device, password change, admin force-logout) even though the JWT
     * signature itself is still valid.
     */
    boolean isSessionActive(UUID userId, UUID sessionId);

    void revokeSession(UUID userId, UUID sessionId, UUID adminId);

    void revokeAllUserSessions(UUID userId, UUID adminId);

    void revokeAllExcept(UUID userId, UUID currentSessionId);

    List<SessionDto> getActiveSessions(UUID userId);

    /**
     * Raw active-session count, used by the login flow to decide whether to block with a
     * device-limit conflict BEFORE creating a new session (unlike the old inline check inside
     * createSession, which created the session unconditionally after silently evicting).
     */
    long countActiveSessions(UUID userId);

    /**
     * Public wrapper around the subscription-plan-derived device cap, needed by AuthService to
     * build the 409 response body (maxDevices) before any session is touched.
     */
    int resolveMaxDevicesForUser(UUID userId);

    /**
     * Self-service revoke with three rules the admin-facing revokeSession() doesn't need:
     * (1) the session must belong to userId — a session that exists for a DIFFERENT user and one
     *     that doesn't exist at all are deliberately indistinguishable (404 either way, so this
     *     endpoint never leaks whether a given sessionId belongs to someone else);
     * (2) an already-revoked session is a successful no-op (idempotent — the caller's goal, "this
     *     device is signed out," is already true);
     * (3) the caller's OWN current session (currentSessionId) cannot be revoked this way — use
     *     logout instead, which also clears cookies.
     * Note: when a session is BOTH already-revoked AND the caller's current session, rule (2)
     * takes precedence over rule (3) — this is an idempotent no-op, not a 400.
     */
    void selfRevokeSession(UUID userId, UUID sessionId, UUID currentSessionId);
}
