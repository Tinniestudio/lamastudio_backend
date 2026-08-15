package com.tinniestudio.api.modules.auth.user.service;

import com.tinniestudio.api.modules.auth.exception.InvalidTokenException;
import com.tinniestudio.api.modules.auth.user.dto.SessionDto;
import com.tinniestudio.api.modules.auth.user.entity.UserSession;
import com.tinniestudio.api.modules.auth.user.repository.UserSessionRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.shared.util.DeviceNameParser;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private final UserSessionRepository userSessionRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final CacheService cacheService;

    private static final String SESSION_KEY_PREFIX = "tinnie:session:";
    private static final Duration SESSION_TTL = Duration.ofDays(7);
    private static final int DEFAULT_MAX_DEVICES = 1;

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public UUID createSession(UUID userId, String rawRefreshToken, HttpServletRequest request) {
        int maxDevices = resolveMaxDevices(userId);
        long activeSessions = userSessionRepository.countByUserIdAndRevokedFalse(userId);

        if (activeSessions >= maxDevices) {
            userSessionRepository.findFirstByUserIdAndRevokedFalseOrderByCreatedAtAsc(userId)
                    .ifPresent(oldest -> {
                        oldest.revoke(null);
                        userSessionRepository.save(oldest);
                        cacheService.delete(sessionKey(userId, oldest.getId()));
                        log.debug("Evicted oldest session {} for user {}", oldest.getId(), userId);
                    });
        }

        String userAgent = request != null ? request.getHeader("User-Agent") : null;
        String ipAddress = resolveClientIp(request);
        String fingerprint = buildFingerprint(userAgent, ipAddress);
        String deviceName = DeviceNameParser.parse(userAgent);

        UserSession session = new UserSession();
        session.setUserId(userId);
        session.setRefreshTokenHash(passwordEncoder.encode(rawRefreshToken));
        session.setDeviceFingerprint(fingerprint);
        session.setDeviceName(deviceName);
        session.setIpAddress(ipAddress);
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        session = userSessionRepository.save(session);

        cacheService.set(sessionKey(userId, session.getId()), "1", SESSION_TTL);
        log.debug("Session created {} for user {}", session.getId(), userId);

        return session.getId();
    }

    // ── Validate ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void validateSession(UUID userId, UUID sessionId) {
        UserSession session = userSessionRepository
                .findByUserIdAndIdAndRevokedFalse(userId, sessionId)
                .orElseThrow(() -> new InvalidTokenException("Session not found or revoked"));

        session.setLastUsedAt(Instant.now());
        userSessionRepository.save(session);
        // Restore cache entry if it expired (e.g., Redis eviction under memory pressure)
        cacheService.set(sessionKey(userId, sessionId), "1", SESSION_TTL);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSessionActive(UUID userId, UUID sessionId) {
        // Fast path: cache hit means the session was active as of its last create/validate/restore.
        if (cacheService.exists(sessionKey(userId, sessionId))) {
            return true;
        }
        // Cache miss could mean revoked OR merely evicted (e.g. Redis memory pressure) — fall
        // back to the lean DB existence check and restore the cache entry so we don't hit the
        // DB again on every subsequent request within the session TTL.
        boolean active = userSessionRepository.existsByUserIdAndIdAndRevokedFalse(userId, sessionId);
        if (active) {
            cacheService.set(sessionKey(userId, sessionId), "1", SESSION_TTL);
        }
        return active;
    }

    // ── Revoke ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void revokeSession(UUID userId, UUID sessionId, UUID adminId) {
        userSessionRepository.findByUserIdAndIdAndRevokedFalse(userId, sessionId)
                .ifPresent(s -> {
                    s.revoke(adminId);
                    userSessionRepository.save(s);
                    cacheService.delete(sessionKey(userId, sessionId));
                });
    }

    @Override
    @Transactional
    public void revokeAllUserSessions(UUID userId, UUID adminId) {
        List<UserSession> sessions = userSessionRepository.findByUserIdAndRevokedFalse(userId);
        for (UserSession s : sessions) {
            s.revoke(adminId);
            cacheService.delete(sessionKey(userId, s.getId()));
        }
        if (!sessions.isEmpty()) {
            userSessionRepository.saveAll(sessions);
            log.info("Admin {} revoked {} sessions for user {}", adminId, sessions.size(), userId);
        }
    }

    @Override
    @Transactional
    public void revokeAllExcept(UUID userId, UUID currentSessionId) {
        List<UserSession> sessions = userSessionRepository.findByUserIdAndRevokedFalse(userId);
        for (UserSession s : sessions) {
            if (!s.getId().equals(currentSessionId)) {
                s.revoke(null);
                cacheService.delete(sessionKey(userId, s.getId()));
            }
        }
        userSessionRepository.saveAll(sessions.stream()
            .filter(s -> !s.getId().equals(currentSessionId))
            .collect(Collectors.toList()));
        log.info("Revoked {} sessions for user {} (kept session {})", sessions.size() - 1, userId, currentSessionId);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<SessionDto> getActiveSessions(UUID userId) {
        return userSessionRepository.findByUserIdAndRevokedFalse(userId).stream()
                .map(s -> SessionDto.builder()
                        .sessionId(s.getId())
                        .deviceName(s.getDeviceName())
                        .ipAddress(s.getIpAddress())
                        .lastUsedAt(s.getLastUsedAt())
                        .current(false)
                        .build())
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int resolveMaxDevices(UUID userId) {
        return userSubscriptionRepository
                .findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                // Ignore subscriptions whose billing period has already ended —
                // the nightly expiration job may not have run yet.
                .filter(sub -> sub.getEndDate() == null || !sub.getEndDate().isBefore(Instant.now()))
                // Read the snapshotted value written at subscription time, not the live plan,
                // so admin plan edits don't silently change existing subscribers' device limits.
                .map(sub -> sub.getMaxDevices() != null ? sub.getMaxDevices() : DEFAULT_MAX_DEVICES)
                .orElse(DEFAULT_MAX_DEVICES);
    }

    private String sessionKey(UUID userId, UUID sessionId) {
        return SESSION_KEY_PREFIX + userId + ":" + sessionId;
    }

    private String buildFingerprint(String userAgent, String ipAddress) {
        String raw = (userAgent != null ? userAgent : "") + "|" + (ipAddress != null ? ipAddress : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) return null;
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xForwardedFor)) return xForwardedFor.split(",")[0].strip();
        String xRealIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(xRealIp)) return xRealIp;
        return request.getRemoteAddr();
    }
}
