package com.lamastudio.backend.modules.auth.user.service;

import com.lamastudio.backend.modules.auth.user.dto.SessionDto;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.UUID;

public interface SessionService {

    UUID createSession(UUID userId, String rawRefreshToken, HttpServletRequest request);

    void validateAndRotate(UUID userId, UUID sessionId, String rawOldRefreshToken, String newRawRefreshToken);

    void revokeSession(UUID userId, UUID sessionId, UUID adminId);

    void revokeAllUserSessions(UUID userId, UUID adminId);

    void revokeAllExcept(UUID userId, UUID currentSessionId);

    List<SessionDto> getActiveSessions(UUID userId);
}
