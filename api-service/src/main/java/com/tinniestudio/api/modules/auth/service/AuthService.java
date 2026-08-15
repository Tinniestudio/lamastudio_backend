package com.tinniestudio.api.modules.auth.service;

import com.tinniestudio.api.modules.auth.dto.*;
import com.tinniestudio.api.modules.auth.exception.BadCredentialsException;
import com.tinniestudio.api.modules.auth.exception.EmailAlreadyExistsException;
import com.tinniestudio.api.modules.auth.exception.InvalidEmailStateException;
import com.tinniestudio.api.modules.auth.exception.InvalidTokenException;
import com.tinniestudio.api.modules.auth.exception.MissingRefreshTokenException;
import com.tinniestudio.api.modules.auth.user.dto.AuthProfileResponse;
import com.tinniestudio.api.modules.auth.user.service.AuthProfileService;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.billing.repository.SubscriptionPlanRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.role.repository.RoleRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.AuthProvider;
import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.RoleName;
import com.tinniestudio.api.shared.entity.SubscriptionPlan;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.UserSubscription;
import com.tinniestudio.api.shared.exception.*;
import com.tinniestudio.api.shared.security.jwt.CookieFactory;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final CookieFactory cookieFactory;
    private final EmailService emailService;
    private final AppProperties appProperties;
    private final SessionService sessionService;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final AuthProfileService authProfileService;

    // ── Register ──────────────────────────────────────────────────────────────

    @Transactional
    public AuthProfileResponse register(RegisterRequest request, HttpServletResponse response) {
        if (request == null) throw new BadRequestException("Request body is required");

        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new EmailAlreadyExistsException("Email address is already registered");
        }

        String password = requireText(request.getPassword(), "Password is required");
        String firstName = requireText(request.getFirstName(), "First name is required");
        String lastName = requireText(request.getLastName(), "Last name is required");

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setProvider(AuthProvider.LOCAL);
        user.setFirstName(firstName.strip());
        user.setLastName(lastName.strip());
        user.setDisplayName(firstName.strip() + " " + lastName.strip());
        user.setAccountStatus(AccountStatus.ACTIVE);
        user.setEmailVerified(false);

        roleRepository.findByName(RoleName.ROLE_USER).ifPresent(user::addRole);

        String verificationToken = UUID.randomUUID().toString();
        user.setEmailVerificationToken(verificationToken);
        user.setEmailVerificationTokenExpiry(
                Instant.now().plus(appProperties.getEmailVerification().getTokenExpiryHours(), ChronoUnit.HOURS));

        user = userRepository.save(user);

        // Auto-create FREE subscription
        createFreeSubscription(user.getId());

        try {
            emailService.sendVerificationEmail(user.getEmail(), resolveDisplayName(user), verificationToken);
        } catch (Exception ex) {
            log.warn("Failed to send verification email to {}: {}", user.getEmail(), ex.getMessage());
        }

        // Register doesn't create a session — user must log in separately after registration
        // (access token issued immediately so user can proceed before verifying email)
        String rawRefreshToken = UUID.randomUUID().toString();
        UUID sessionId = sessionService.createSession(user.getId(), rawRefreshToken, null);
        issueTokenCookies(user, sessionId, response);

        log.info("New user registered: {}", user.getEmail());
        // return toAuthResponse(user, "Registration successful. Please check your email to verify your account.");
        return authProfileService.getProfile(user.getId(), sessionId, "Registration successful. Please check your email to verify your account.");
    }

    // ── Login ─────────────────────────────────────────────────────────────────
    @Transactional
    public AuthProfileResponse login(LoginRequest request, HttpServletRequest httpRequest,
                               HttpServletResponse response) {
        String email = request != null ? request.getEmail() : null;
        String password = request != null ? request.getPassword() : null;

        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            throw new BadCredentialsException("Invalid email or password");
        }

        String normalizedEmail = email.toLowerCase().strip();

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, password));
        } catch (org.springframework.security.authentication.BadCredentialsException ex) {
            throw new BadCredentialsException("Invalid email or password");
        } catch (DisabledException ex) {
            throw new AccountNotActiveException("Account is disabled");
        } catch (LockedException ex) {
            throw new AccountNotActiveException("Account is locked");
        } catch (AuthenticationException ex) {
            throw new BadCredentialsException("Invalid email or password");
        }

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String rawRefreshToken = generateSecureToken();
        UUID sessionId = sessionService.createSession(user.getId(), rawRefreshToken, httpRequest);
        issueTokenCookies(user, sessionId, response);

        log.info("User logged in: {}", user.getEmail());
        return authProfileService.getProfile(user.getId(), sessionId, "Login successful");
    }

    // ── Refresh ───────────────────────────────────────────────────────────────
    @Transactional
    public AuthProfileResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = extractRefreshToken(request);
        if (refreshToken == null) {
            throw new MissingRefreshTokenException("Refresh token is missing");
        }

        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }

        Claims claims = jwtTokenProvider.parseRefreshToken(refreshToken);
        UUID userId = UUID.fromString(claims.getSubject());
        String sessionIdStr = claims.get(JwtTokenProvider.CLAIM_SESSION_ID, String.class);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("User not found for refresh token"));

        if (!user.isActive()) {
            throw new AccountNotActiveException("Account is " + user.getAccountStatus().name().toLowerCase());
        }

        UUID sessionId = sessionIdStr != null ? UUID.fromString(sessionIdStr) : null;

        if (sessionId != null) {
            sessionService.validateSession(userId, sessionId);
        }

        String newAccessToken = jwtTokenProvider.generateAccessToken(user, sessionId);
        cookieFactory.addAccessTokenCookie(response, newAccessToken);

        return authProfileService.getProfile(user.getId(), sessionId, "Token refreshed successfully");

        // return toAuthResponse(user, null);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        // Extract sessionId from request attribute (set by JwtAuthenticationFilter)
        String sessionIdAttr = (String) request.getAttribute("sessionId");
        if (StringUtils.hasText(sessionIdAttr)) {
            UUID userId = null;
            UUID sessionId = null;
            try {
                // Parsing the refresh token/session id can legitimately fail for edge cases
                // (missing cookie, already-expired refresh token) — that's not a real failure,
                // there's simply nothing to revoke, so logout should still succeed for the
                // client (cookies get cleared below regardless).
                String tokenStr = extractRefreshToken(request);
                Claims claims = tokenStr != null && jwtTokenProvider.validateRefreshToken(tokenStr)
                        ? jwtTokenProvider.parseRefreshToken(tokenStr) : null;
                userId = claims != null ? UUID.fromString(claims.getSubject()) : null;
                sessionId = UUID.fromString(sessionIdAttr);
            } catch (Exception ex) {
                log.debug("Could not parse session/refresh token on logout: {}", ex.getMessage());
            }
            if (userId != null) {
                try {
                    // Unlike the parsing step above, a failure HERE means the session was NOT
                    // actually revoked server-side even though the client is about to be told
                    // logout succeeded (cookies still get cleared) — that combination previously
                    // vanished into a debug-level log line, invisible outside verbose debugging.
                    // Surfaced at warn with the real exception so it's actually seen.
                    sessionService.revokeSession(userId, sessionId, null);
                } catch (Exception ex) {
                    log.warn("Session revocation failed during logout for user {} session {} — " +
                            "client will still be logged out locally, but the session row was not revoked",
                            userId, sessionId, ex);
                }
            }
        }
        cookieFactory.clearAuthCookies(response);
    }

    // ── Email Verification ────────────────────────────────────────────────────

    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByEmailVerificationToken(token)
                .orElseThrow(() -> new InvalidTokenException("Email verification token is invalid or expired"));

        if (user.getEmailVerificationTokenExpiry() == null ||
                Instant.now().isAfter(user.getEmailVerificationTokenExpiry())) {
            throw new com.tinniestudio.api.shared.exception.EmailTokenExpiredException(
                    "Email verification token has expired. Please request a new one.");
        }

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenExpiry(null);
        userRepository.save(user);
        log.info("Email verified for user: {}", user.getEmail());
    }

    @Transactional
    public VerifyEmailResponse verifyEmailEnhanced(String token) {
        User user = userRepository.findByEmailVerificationToken(token).orElse(null);

        if (user == null) {
            throw new InvalidTokenException("Email verification token is invalid or does not exist");
        }

        if (user.getEmailVerificationToken() == null) {
            if (user.isEmailVerified()) {
                return VerifyEmailResponse.builder()
                        .message("Email already verified").alreadyVerified(true).timestamp(Instant.now()).build();
            }
            throw new InvalidTokenException("Email verification token is invalid or does not exist");
        }

        if (user.getEmailVerificationTokenExpiry() == null ||
                Instant.now().isAfter(user.getEmailVerificationTokenExpiry())) {
            throw new com.tinniestudio.api.shared.exception.EmailTokenExpiredException(
                    "Email verification token has expired. Please request a new one.");
        }

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenExpiry(null);
        userRepository.save(user);
        log.info("Email verified for user: {}", user.getEmail());
        return VerifyEmailResponse.builder()
                .message("Email verified successfully").alreadyVerified(false).timestamp(Instant.now()).build();
    }

    @Transactional
    public void resendVerificationEmail(String email) {
        String normalizedEmail = normalizeEmail(email);
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("No user found with this email address"));

        if (user.isEmailVerified()) throw new InvalidEmailStateException("Email is already verified");
        if (user.getProvider() != AuthProvider.LOCAL) {
            throw new InvalidEmailStateException("Cannot resend verification for OAuth-only accounts.");
        }
        if (!user.isActive()) {
            throw new AccountNotActiveException("Account is " + user.getAccountStatus().name().toLowerCase());
        }

        String newToken = UUID.randomUUID().toString();
        user.setEmailVerificationToken(newToken);
        user.setEmailVerificationTokenExpiry(
                Instant.now().plus(appProperties.getEmailVerification().getTokenExpiryHours(), ChronoUnit.HOURS));
        userRepository.save(user);

        try {
            emailService.sendVerificationEmail(user.getEmail(), resolveDisplayName(user), newToken);
            log.info("Resent verification email to: {}", user.getEmail());
        } catch (Exception ex) {
            log.warn("Failed to send verification email to {}: {}", user.getEmail(), ex.getMessage());
            throw new RuntimeException("Failed to send verification email. Please try again later.");
        }
    }

    // ── Forgot / Reset Password ───────────────────────────────────────────────

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        String email = normalizeEmail(request.getEmail());
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getProvider() != AuthProvider.LOCAL) return;

            String resetToken = UUID.randomUUID().toString();
            user.setPasswordResetToken(resetToken);
            user.setPasswordResetTokenExpiry(
                    Instant.now().plus(appProperties.getPasswordReset().getTokenExpiryHours(), ChronoUnit.HOURS));
            userRepository.save(user);

            try {
                emailService.sendPasswordResetEmail(user.getEmail(), resolveDisplayName(user), resetToken);
            } catch (Exception ex) {
                log.warn("Failed to send password reset email to {}: {}", user.getEmail(), ex.getMessage());
            }
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        requireText(request.getToken(), "Password reset token is required");
        requireText(request.getNewPassword(), "New password is required");

        User user = userRepository.findByPasswordResetToken(request.getToken())
                .orElseThrow(() -> new InvalidTokenException("Password reset token is invalid or expired"));

        if (user.getPasswordResetTokenExpiry() == null ||
                Instant.now().isAfter(user.getPasswordResetTokenExpiry())) {
            throw new InvalidTokenException("Password reset token has expired");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiry(null);
        userRepository.save(user);
        log.info("Password reset for user: {}", user.getEmail());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createFreeSubscription(UUID userId) {
        subscriptionPlanRepository.findByNameIgnoreCase("FREE").ifPresent(freePlan -> {
            UserSubscription sub = new UserSubscription();
            sub.setUserId(userId);
            sub.setPlan(freePlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setStartDate(Instant.now());
            sub.setContentWatchesUsed(0);
            sub.setMaxDevices(freePlan.getMaxDevices() != null ? freePlan.getMaxDevices() : 1);
            userSubscriptionRepository.save(sub);
        });
    }

    private void issueTokenCookies(User user, UUID sessionId, HttpServletResponse response) {
        String accessToken = jwtTokenProvider.generateAccessToken(user, sessionId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user, sessionId);
        cookieFactory.addAuthCookies(response, accessToken, refreshToken);
    }

    private String generateSecureToken() {
        return UUID.randomUUID().toString() + "-" + UUID.randomUUID();
    }

    private String extractRefreshToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> CookieFactory.REFRESH_TOKEN_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst().orElse(null);
    }

    private AuthResponse toAuthResponse(User user, String message) {
        Set<String> roles = user.getRoles().stream()
                .map(r -> r.getName().name())
                .collect(Collectors.toSet());
        return AuthResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .displayName(user.getDisplayName())
                .avatarUrl(user.getAvatarUrl())
                .roles(roles)
                .provider(user.getProvider().name())
                .emailVerified(user.isEmailVerified())
                .message(message)
                .build();
    }

    private String resolveDisplayName(User user) {
        if (user == null) return "User";
        if (StringUtils.hasText(user.getDisplayName())) return user.getDisplayName().strip();
        String fn = user.getFirstName(), ln = user.getLastName();
        if (StringUtils.hasText(fn) && StringUtils.hasText(ln)) return (fn.strip() + " " + ln.strip()).trim();
        if (StringUtils.hasText(fn)) return fn.strip();
        if (StringUtils.hasText(ln)) return ln.strip();
        return "User";
    }

    private String normalizeEmail(String email) {
        return requireText(email, "Email is required").toLowerCase().strip();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) throw new BadRequestException(message);
        return value;
    }
}
