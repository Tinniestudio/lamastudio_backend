package com.lamastudio.backend.auth.service;

import com.lamastudio.backend.auth.dto.*;
import com.lamastudio.backend.auth.jwt.CookieFactory;
import com.lamastudio.backend.auth.jwt.JwtTokenProvider;
import com.lamastudio.backend.config.AppProperties;
import com.lamastudio.backend.exception.*;
import com.lamastudio.backend.role.entity.RoleName;
import com.lamastudio.backend.role.repository.RoleRepository;
import com.lamastudio.backend.user.entity.AccountStatus;
import com.lamastudio.backend.user.entity.AuthProvider;
import com.lamastudio.backend.user.entity.User;
import com.lamastudio.backend.user.repository.UserRepository;
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

    // ── Register ──────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse register(RegisterRequest request, HttpServletResponse response) {
        if (userRepository.existsByEmail(request.getEmail().toLowerCase())) {
            throw new EmailAlreadyExistsException("Email address is already registered");
        }

        User user = new User();
        user.setEmail(request.getEmail().toLowerCase().strip());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setProvider(AuthProvider.LOCAL);
        user.setFirstName(request.getFirstName().strip());
        user.setLastName(request.getLastName().strip());
        user.setDisplayName(request.getFirstName().strip() + " " + request.getLastName().strip());
        user.setAccountStatus(AccountStatus.ACTIVE);
        user.setEmailVerified(false);

        java.util.Optional<com.lamastudio.backend.role.entity.Role> roleOpt = roleRepository
                .findByName(RoleName.ROLE_USER);
        if (roleOpt.isPresent())
            user.addRole(roleOpt.get());

        // Generate email verification token
        String verificationToken = UUID.randomUUID().toString();
        user.setEmailVerificationToken(verificationToken);
        user.setEmailVerificationTokenExpiry(
                Instant.now().plus(appProperties.getEmailVerification().getTokenExpiryHours(), ChronoUnit.HOURS));

        user = userRepository.save(user);

        // Send verification email async (fire-and-forget)
        emailService.sendVerificationEmail(user.getEmail(), verificationToken);

        // Issue tokens immediately so user can proceed (email verification enforced at
        // sensitive ops)
        issueTokenCookies(user, response);

        log.info("New user registered: {}", user.getEmail());
        return toAuthResponse(user, "Registration successful. Please check your email to verify your account.");
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request, HttpServletResponse response) {

        String email = request != null ? request.getEmail() : null;
        String password = request != null ? request.getPassword() : null;

        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            throw new BadCredentialsException("Invalid email or password");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            email.toLowerCase().strip(),
                            password));
        } catch (org.springframework.security.authentication.BadCredentialsException ex) {
            throw new BadCredentialsException("Invalid email or password");
        } catch (DisabledException ex) {
            throw new AccountNotActiveException("Account is disabled");
        } catch (LockedException ex) {
            throw new AccountNotActiveException("Account is locked");
        } catch (AuthenticationException ex) {
            throw new BadCredentialsException("Invalid email or password");
        }

        // Fetch user AFTER successful auth
        User user = userRepository.findByEmail(request.getEmail().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        issueTokenCookies(user, response);

        log.info("User logged in: {}", user.getEmail());
        return toAuthResponse(user, null);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = extractRefreshToken(request);

        if (refreshToken == null) {
            throw new com.lamastudio.backend.exception.MissingRefreshTokenException("Refresh token is missing");
        }

        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            throw new InvalidTokenException("Refresh token is missing or invalid");
        }

        Claims claims = jwtTokenProvider.parseRefreshToken(refreshToken);
        UUID userId = UUID.fromString(claims.getSubject());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("User not found for refresh token"));

        if (!user.isActive()) {
            throw new AccountNotActiveException("Account is " + user.getAccountStatus().name().toLowerCase());
        }

        issueTokenCookies(user, response);

        return toAuthResponse(user, null);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    public void logout(HttpServletResponse response) {
        cookieFactory.clearAuthCookies(response);
    }

    // ── Email Verification ────────────────────────────────────────────────────

    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByEmailVerificationToken(token)
                .orElseThrow(() -> new InvalidTokenException("Email verification token is invalid or expired"));

        if (user.getEmailVerificationTokenExpiry() == null ||
                Instant.now().isAfter(user.getEmailVerificationTokenExpiry())) {
            throw new InvalidTokenException("Email verification token has expired");
        }

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenExpiry(null);
        userRepository.save(user);

        log.info("Email verified for user: {}", user.getEmail());
    }

    // ── Forgot Password ───────────────────────────────────────────────────────

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        // Always return 200 regardless of whether email exists (prevent enumeration)
        userRepository.findByEmail(request.getEmail().toLowerCase()).ifPresent(user -> {
            if (user.getProvider() != AuthProvider.LOCAL) {
                // OAuth users have no password — silently ignore
                return;
            }

            String resetToken = UUID.randomUUID().toString();
            user.setPasswordResetToken(resetToken);
            user.setPasswordResetTokenExpiry(
                    Instant.now().plus(appProperties.getPasswordReset().getTokenExpiryHours(), ChronoUnit.HOURS));
            userRepository.save(user);

            emailService.sendPasswordResetEmail(user.getEmail(), resetToken);
        });
    }

    // ── Reset Password ────────────────────────────────────────────────────────

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
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

    private void issueTokenCookies(User user, HttpServletResponse response) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);
        cookieFactory.addAuthCookies(response, accessToken, refreshToken);
    }

    private String extractRefreshToken(HttpServletRequest request) {
        if (request.getCookies() == null)
            return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> CookieFactory.REFRESH_TOKEN_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
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

}
