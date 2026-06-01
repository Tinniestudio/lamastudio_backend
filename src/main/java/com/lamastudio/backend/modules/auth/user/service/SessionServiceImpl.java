package com.lamastudio.backend.modules.auth.user.service;

import com.lamastudio.backend.modules.auth.exception.InvalidTokenException;
import com.lamastudio.backend.modules.auth.user.dto.SessionDto;
import com.lamastudio.backend.modules.auth.user.entity.UserSession;
import com.lamastudio.backend.modules.auth.user.repository.UserSessionRepository;
import com.lamastudio.backend.modules.billing.repository.UserSubscriptionRepository;
import com.lamastudio.backend.shared.entity.UserSubscription;
import com.lamastudio.backend.shared.cache.CacheService;
import com.lamastudio.backend.shared.config.AppProperties;
import com.lamastudio.backend.shared.entity.DomainEnums.SubscriptionStatus;
import com.lamastudio.backend.shared.util.DeviceNameParser;

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
    private final AppProperties appProperties;

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

    // ── Validate + Rotate ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public void validateAndRotate(UUID userId, UUID sessionId, String rawOldRefreshToken,
                                   String newRawRefreshToken) {
        if (!cacheService.exists(sessionKey(userId, sessionId))) {
            throw new InvalidTokenException("Session not found or revoked");
        }

        UserSession session = userSessionRepository
                .findByUserIdAndIdAndRevokedFalse(userId, sessionId)
                .orElseThrow(() -> new InvalidTokenException("Session is revoked"));

        if (!passwordEncoder.matches(rawOldRefreshToken, session.getRefreshTokenHash())) {
            session.revoke(null);
            userSessionRepository.save(session);
            cacheService.delete(sessionKey(userId, sessionId));
            throw new InvalidTokenException("Refresh token has been rotated — possible replay attack");
        }

        session.setRefreshTokenHash(passwordEncoder.encode(newRawRefreshToken));
        session.setLastUsedAt(Instant.now());
        userSessionRepository.save(session);
        cacheService.set(sessionKey(userId, sessionId), "1", SESSION_TTL);
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
                .map(sub -> sub.getPlan() != null && sub.getPlan().getMaxDevices() != null
                        ? sub.getPlan().getMaxDevices()
                        : DEFAULT_MAX_DEVICES)
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
