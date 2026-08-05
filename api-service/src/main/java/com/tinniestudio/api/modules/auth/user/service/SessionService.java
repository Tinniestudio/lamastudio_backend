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
}
