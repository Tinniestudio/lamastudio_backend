package com.tinniestudio.api.modules.user.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.user.dto.*;
import com.tinniestudio.api.modules.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "User Profile", description = "User profile management endpoints")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    @Operation(summary = "Get current user's full profile")
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getProfile(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(userProfileService.getProfile(CurrentUser.id(principal)));
    }

    @Operation(summary = "Partially update current user's profile")
    @PatchMapping("/me")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userProfileService.updateProfile(CurrentUser.id(principal), request));
    }

    @Operation(summary = "Update email notification preference")
    @PatchMapping("/me/notifications")
    public ResponseEntity<Map<String, Boolean>> updateNotifications(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody UpdateNotificationRequest request) {
        userProfileService.updateNotifications(CurrentUser.id(principal), request);
        return ResponseEntity.ok(Map.of("notificationEmail", request.getNotificationEmail()));
    }

    @Operation(summary = "Set profile avatar — UPLOAD mode returns presigned URL, URL mode sets directly")
    @PatchMapping("/me/avatar")
    public ResponseEntity<Object> updateAvatar(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody AvatarUpdateRequest request) {
        UUID userId = CurrentUser.id(principal);
        if (request.getMode() == AvatarUpdateRequest.Mode.URL) {
            String url = userProfileService.setAvatarByUrl(userId, request.getAvatarUrl());
            return ResponseEntity.ok(Map.of("avatarUrl", url));
        }
        return ResponseEntity.ok(userProfileService.initiateAvatarUpload(userId, request));
    }

    @Operation(summary = "Confirm avatar file upload is complete and update avatarUrl")
    @PostMapping("/me/avatar/confirm")
    public ResponseEntity<Map<String, String>> confirmAvatarUpload(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody AvatarConfirmRequest request) {
        String url = userProfileService.confirmAvatarUpload(CurrentUser.id(principal), request);
        return ResponseEntity.ok(Map.of("avatarUrl", url));
    }

    @Operation(summary = "Change account password (LOCAL accounts only)")
    @PatchMapping("/me/password")
    public ResponseEntity<Map<String, String>> changePassword(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody UpdatePasswordRequest request,
            HttpServletRequest httpRequest) {
        UUID currentSessionId = sessionId(httpRequest);
        userProfileService.changePassword(CurrentUser.id(principal), request, currentSessionId);
        return ResponseEntity.ok(Map.of(
            "message", "Password updated successfully. Other sessions have been logged out."));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────


    private UUID sessionId(HttpServletRequest request) {
        Object sid = request.getAttribute("sessionId");
        return sid != null ? UUID.fromString(sid.toString()) : null;
    }
}
