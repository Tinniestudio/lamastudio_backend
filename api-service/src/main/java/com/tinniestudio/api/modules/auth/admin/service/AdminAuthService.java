package com.tinniestudio.api.modules.auth.admin.service;

import com.tinniestudio.api.modules.auth.admin.dto.AdminAuthResponse;
import com.tinniestudio.api.modules.auth.admin.dto.AdminLoginRequest;
import com.tinniestudio.api.modules.auth.admin.dto.AdminRegisterRequest;
import com.tinniestudio.api.modules.auth.admin.entity.Admin;
import com.tinniestudio.api.modules.auth.admin.entity.AdminRoleName;
import com.tinniestudio.api.modules.auth.admin.entity.AdminSession;
import com.tinniestudio.api.modules.auth.admin.repository.AdminRepository;
import com.tinniestudio.api.modules.auth.admin.repository.AdminSessionRepository;
import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.modules.auth.exception.BadCredentialsException;
import com.tinniestudio.api.modules.auth.exception.InvalidTokenException;
import com.tinniestudio.api.modules.auth.exception.MissingRefreshTokenException;
import com.tinniestudio.api.shared.exception.*;
import com.tinniestudio.api.shared.security.jwt.AdminJwtTokenProvider;
import com.tinniestudio.api.shared.security.jwt.CookieFactory;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminRepository adminRepository;
    private final AdminSessionRepository adminSessionRepository;
    private final AdminJwtTokenProvider adminJwtTokenProvider;
    private final CookieFactory cookieFactory;
    private final PasswordEncoder passwordEncoder;
    private final CacheService cacheService;
    private final AppProperties appProperties;
    private final EmailService emailService;

    private static final String SESSION_KEY_PREFIX = "tinnie:admin:session:";
    private static final Duration SESSION_TTL = Duration.ofDays(7);

    // ── Login ─────────────────────────────────────────────────────────────────

    @Transactional
    public AdminAuthResponse login(AdminLoginRequest request, HttpServletRequest httpRequest,
                                   HttpServletResponse response) {
        String email = normalizeEmail(request.getEmail());

        Admin admin = adminRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), admin.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!admin.isActive()) {
            throw new AccountNotActiveException("Admin account is " + admin.getAccountStatus().name().toLowerCase());
        }

        // Revoke any existing active sessions (single active session per admin)
        List<AdminSession> activeSessions = adminSessionRepository.findByAdminIdAndRevokedFalse(admin.getId());
        for (AdminSession existing : activeSessions) {
            existing.revoke();
            cacheService.delete(sessionKey(admin.getId(), existing.getId()));
        }
        if (!activeSessions.isEmpty()) {
            adminSessionRepository.saveAll(activeSessions);
        }

        // Create new session
        AdminSession session = createAdminSession(admin, httpRequest);
        String rawRefreshToken = adminJwtTokenProvider.generateRefreshToken(admin, session.getId());
        session.setRefreshTokenHash(passwordEncoder.encode(rawRefreshToken));
        adminSessionRepository.save(session);

        cacheService.set(sessionKey(admin.getId(), session.getId()), "1", SESSION_TTL);

        String accessToken = adminJwtTokenProvider.generateAccessToken(admin, session.getId());
        cookieFactory.addAdminAuthCookies(response, accessToken, rawRefreshToken);

        log.info("Admin logged in: {}", admin.getEmail());
        return toResponse(admin, null);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Transactional
    public AdminAuthResponse refresh(HttpServletRequest httpRequest, HttpServletResponse response) {
        String rawRefreshToken = extractAdminRefreshToken(httpRequest);
        if (!StringUtils.hasText(rawRefreshToken)) {
            throw new MissingRefreshTokenException("Admin refresh token is missing");
        }

        if (!adminJwtTokenProvider.validateRefreshToken(rawRefreshToken)) {
            throw new InvalidTokenException("Admin refresh token is invalid or expired");
        }

        Claims claims = adminJwtTokenProvider.parseRefreshToken(rawRefreshToken);
        UUID adminId = UUID.fromString(claims.getSubject());
        String sessionIdStr = claims.get(AdminJwtTokenProvider.CLAIM_SESSION_ID, String.class);

        if (sessionIdStr == null) {
            throw new InvalidTokenException("Admin refresh token missing session id");
        }
        UUID sessionId = UUID.fromString(sessionIdStr);

        // Redis fast-path: session must exist in cache
        if (!cacheService.exists(sessionKey(adminId, sessionId))) {
            throw new InvalidTokenException("Admin session not found or revoked");
        }

        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new InvalidTokenException("Admin not found"));

        AdminSession session = adminSessionRepository
                .findByAdminIdAndIdAndRevokedFalse(adminId, sessionId)
                .orElseThrow(() -> new InvalidTokenException("Admin session is revoked"));

        // Replay detection: hash mismatch means stale token
        if (!passwordEncoder.matches(rawRefreshToken, session.getRefreshTokenHash())) {
            session.revoke();
            adminSessionRepository.save(session);
            cacheService.delete(sessionKey(adminId, sessionId));
            throw new InvalidTokenException("Admin refresh token has been rotated — possible replay attack");
        }

        // Rotate refresh token
        String newRawRefreshToken = adminJwtTokenProvider.generateRefreshToken(admin, sessionId);
        session.setRefreshTokenHash(passwordEncoder.encode(newRawRefreshToken));
        session.setLastUsedAt(Instant.now());
        adminSessionRepository.save(session);
        cacheService.set(sessionKey(adminId, sessionId), "1", SESSION_TTL);

        String newAccessToken = adminJwtTokenProvider.generateAccessToken(admin, sessionId);
        cookieFactory.addAdminAuthCookies(response, newAccessToken, newRawRefreshToken);

        return toResponse(admin, null);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Transactional
    public void logout(HttpServletRequest httpRequest, HttpServletResponse response) {
        String sessionIdAttr = (String) httpRequest.getAttribute("adminSessionId");
        if (StringUtils.hasText(sessionIdAttr)) {
            try {
                UUID sessionId = UUID.fromString(sessionIdAttr);
                String adminIdStr = extractAdminIdFromCookie(httpRequest);
                if (adminIdStr != null) {
                    UUID adminId = UUID.fromString(adminIdStr);
                    adminSessionRepository.findByAdminIdAndIdAndRevokedFalse(adminId, sessionId)
                            .ifPresent(s -> {
                                s.revoke();
                                adminSessionRepository.save(s);
                                cacheService.delete(sessionKey(adminId, sessionId));
                            });
                }
            } catch (Exception ex) {
                log.debug("Could not revoke admin session on logout: {}", ex.getMessage());
            }
        }
        cookieFactory.clearAdminAuthCookies(response);
    }

    // ── Me ────────────────────────────────────────────────────────────────────

    public AdminAuthResponse getProfile(UUID adminId) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
        return toResponse(admin, null);
    }

    // ── Register sub-admin ────────────────────────────────────────────────────

    @Transactional
    public AdminAuthResponse registerAdmin(AdminRegisterRequest request) {
        if (request.getRole() == AdminRoleName.SUPER_ADMIN
                && adminRepository.existsByRolesContaining(AdminRoleName.SUPER_ADMIN)) {
            throw new BadRequestException("Only one super admin is permitted in the system");
        }

        if (adminRepository.existsByEmail(request.getEmail().toLowerCase().strip())) {
            throw new BadRequestException("Admin email already registered");
        }

        Admin admin = new Admin();
        admin.setEmail(request.getEmail().toLowerCase().strip());
        admin.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        admin.setFirstName(request.getFirstName() != null ? request.getFirstName().strip() : null);
        admin.setLastName(request.getLastName() != null ? request.getLastName().strip() : null);
        admin.getRoles().add(request.getRole());
        admin = adminRepository.save(admin);

        log.info("Sub-admin created: {} with role {}", admin.getEmail(), request.getRole());
        return toResponse(admin, "Admin created successfully");
    }

    // ── Forgot password ───────────────────────────────────────────────────────

    @Transactional
    public void forgotPassword(String email) {
        String normalizedEmail = normalizeEmail(email);
        adminRepository.findByEmail(normalizedEmail).ifPresent(admin -> {
            String resetToken = UUID.randomUUID().toString();
            admin.setPasswordResetToken(resetToken);
            admin.setPasswordResetTokenExpiry(
                    Instant.now().plus(
                            appProperties.getAdminPasswordReset().getTokenExpiryMinutes(),
                            ChronoUnit.MINUTES));
            admin.setPasswordResetTokenInvalidated(false);
            adminRepository.save(admin);

            // Notify super admin of any reset request
            adminRepository.findFirstByRole(AdminRoleName.SUPER_ADMIN).ifPresent(superAdmin -> {
                if (!superAdmin.getId().equals(admin.getId())) {
                    try {
                        emailService.sendAdminPasswordResetAlert(superAdmin.getEmail(), admin.getEmail());
                    } catch (Exception ex) {
                        log.warn("Failed to send admin reset alert to super admin: {}", ex.getMessage());
                    }
                }
            });

            try {
                emailService.sendAdminPasswordResetEmail(admin.getEmail(), resetToken);
            } catch (Exception ex) {
                log.warn("Failed to send admin password reset email: {}", ex.getMessage());
            }
        });
    }

    // ── Reset password ────────────────────────────────────────────────────────

    @Transactional
    public void resetPassword(String token, String newPassword) {
        Admin admin = adminRepository.findByPasswordResetToken(token)
                .orElseThrow(() -> new InvalidTokenException("Password reset token is invalid"));

        if (admin.isPasswordResetTokenInvalidated()) {
            throw new InvalidTokenException("Password reset token has been invalidated");
        }

        if (admin.getPasswordResetTokenExpiry() == null
                || Instant.now().isAfter(admin.getPasswordResetTokenExpiry())) {
            admin.setPasswordResetTokenInvalidated(true);
            adminRepository.save(admin);
            throw new InvalidTokenException("Password reset token has expired");
        }

        if (newPassword.length() < 8) {
            // Invalidate token on weak password attempt (stricter than user reset)
            admin.setPasswordResetTokenInvalidated(true);
            adminRepository.save(admin);
            throw new BadRequestException("Password must be at least 8 characters");
        }

        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        admin.setPasswordResetToken(null);
        admin.setPasswordResetTokenExpiry(null);
        admin.setPasswordResetTokenInvalidated(false);
        adminRepository.save(admin);

        // Revoke all active admin sessions on successful reset
        List<AdminSession> sessions = adminSessionRepository.findByAdminIdAndRevokedFalse(admin.getId());
        for (AdminSession s : sessions) {
            s.revoke();
            cacheService.delete(sessionKey(admin.getId(), s.getId()));
        }
        if (!sessions.isEmpty()) {
            adminSessionRepository.saveAll(sessions);
        }

        log.info("Admin password reset: {}", admin.getEmail());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AdminSession createAdminSession(Admin admin, HttpServletRequest request) {
        AdminSession session = new AdminSession();
        session.setAdminId(admin.getId());
        session.setRefreshTokenHash("placeholder"); // replaced immediately after generation
        session.setIpAddress(resolveClientIp(request));
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        return adminSessionRepository.save(session);
    }

    private String sessionKey(UUID adminId, UUID sessionId) {
        return SESSION_KEY_PREFIX + adminId + ":" + sessionId;
    }

    private String extractAdminRefreshToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> CookieFactory.ADMIN_REFRESH_TOKEN_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private String extractAdminIdFromCookie(HttpServletRequest request) {
        String token = null;
        if (request.getCookies() != null) {
            token = Arrays.stream(request.getCookies())
                    .filter(c -> CookieFactory.ADMIN_ACCESS_TOKEN_COOKIE.equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (token == null || !adminJwtTokenProvider.validateAccessToken(token)) return null;
        return adminJwtTokenProvider.parseAccessToken(token).getSubject();
    }

    private AdminAuthResponse toResponse(Admin admin, String message) {
        return AdminAuthResponse.builder()
                .adminId(admin.getId())
                .email(admin.getEmail())
                .firstName(admin.getFirstName())
                .lastName(admin.getLastName())
                .roles(admin.getRoles())
                .message(message)
                .build();
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) throw new BadRequestException("Email is required");
        return email.toLowerCase().strip();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xForwardedFor)) return xForwardedFor.split(",")[0].strip();
        String xRealIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(xRealIp)) return xRealIp;
        return request.getRemoteAddr();
    }
}
