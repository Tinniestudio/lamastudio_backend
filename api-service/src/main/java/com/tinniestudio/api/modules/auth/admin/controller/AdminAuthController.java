package com.tinniestudio.api.modules.auth.admin.controller;

import com.tinniestudio.api.modules.auth.admin.dto.*;
import com.tinniestudio.api.modules.auth.admin.service.AdminAuthService;
import com.tinniestudio.api.modules.auth.admin.service.AdminBootstrapService;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.ratelimit.RateLimit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth/admin")
@RequiredArgsConstructor
@Tag(name = "Admin Authentication", description = "Isolated admin auth endpoints — separate tokens, cookies, and session lifecycle from user auth")
public class AdminAuthController {

    private final AdminBootstrapService adminBootstrapService;
    private final AdminAuthService adminAuthService;
    private final SessionService sessionService;

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    @Operation(summary = "Bootstrap super admin (one-time)", description = "Creates the first super admin account. Disabled permanently after first successful use.")
    @SecurityRequirements({})
    @RateLimit(maxRequests = 5, windowMinutes = 15, keyStrategy = "IP_ONLY")
    @PostMapping("/bootstrap")
    public ResponseEntity<AdminAuthResponse> bootstrap(@Valid @RequestBody AdminBootstrapRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminBootstrapService.bootstrapSuperAdmin(request));
    }

    // ── Login / Refresh / Logout ──────────────────────────────────────────────

    @Operation(summary = "Admin login")
    @SecurityRequirements({})
    @RateLimit(maxRequests = 5, windowMinutes = 15, keyStrategy = "IP_ONLY")
    @PostMapping("/login")
    public ResponseEntity<AdminAuthResponse> login(
            @Valid @RequestBody AdminLoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        return ResponseEntity.ok(adminAuthService.login(request, httpRequest, response));
    }

    @Operation(summary = "Refresh admin access token")
    @SecurityRequirements({})
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(HttpServletRequest request, HttpServletResponse response) {
        adminAuthService.refresh(request, response);
        return ResponseEntity.ok(Map.of("message", "Token refreshed"));
    }

    @Operation(summary = "Admin logout")
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request, HttpServletResponse response) {
        adminAuthService.logout(request, response);
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @Operation(summary = "Get current admin profile")
    @GetMapping("/me")
    public ResponseEntity<AdminAuthResponse> me(@AuthenticationPrincipal UserDetails userDetails) {
        UUID adminId = UUID.fromString(userDetails.getUsername());
        return ResponseEntity.ok(adminAuthService.getProfile(adminId));
    }

    // ── Sub-admin management ──────────────────────────────────────────────────

    @Operation(summary = "Create sub-admin (SUPER_ADMIN only)")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/register")
    public ResponseEntity<AdminAuthResponse> register(@Valid @RequestBody AdminRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminAuthService.registerAdmin(request));
    }

    // ── Password reset ────────────────────────────────────────────────────────

    @Operation(summary = "Request admin password reset email")
    @SecurityRequirements({})
    @RateLimit(maxRequests = 1, windowMinutes = 60, keyStrategy = "IP_ONLY")
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) throw new BadRequestException("Email is required");
        adminAuthService.forgotPassword(email);
        return ResponseEntity.ok(Map.of("message", "If this admin email is registered, a reset link has been sent."));
    }

    @Operation(summary = "Reset admin password (15-min token, stricter rules)")
    @SecurityRequirements({})
    @PatchMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String newPassword = body.get("newPassword");
        if (token == null || token.isBlank()) throw new BadRequestException("Token is required");
        if (newPassword == null || newPassword.isBlank()) throw new BadRequestException("New password is required");
        adminAuthService.resetPassword(token, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully. All admin sessions have been revoked."));
    }

    // ── Session management (admin force-logout) ───────────────────────────────

    @Operation(summary = "Revoke all sessions for a user (SUPER_ADMIN or MODERATOR)")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','MODERATOR')")
    @DeleteMapping("/users/{userId}/sessions")
    public ResponseEntity<Map<String, String>> revokeAllUserSessions(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID adminId = UUID.fromString(userDetails.getUsername());
        sessionService.revokeAllUserSessions(userId, adminId);
        return ResponseEntity.ok(Map.of("userId", userId.toString(), "message", "All sessions revoked"));
    }

    @Operation(summary = "Revoke a specific session for a user")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','MODERATOR')")
    @DeleteMapping("/users/{userId}/sessions/{sessionId}")
    public ResponseEntity<Map<String, String>> revokeUserSession(
            @PathVariable UUID userId,
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID adminId = UUID.fromString(userDetails.getUsername());
        sessionService.revokeSession(userId, sessionId, adminId);
        return ResponseEntity.ok(Map.of("sessionId", sessionId.toString(), "message", "Session revoked"));
    }
}
