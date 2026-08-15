package com.tinniestudio.api.modules.notification.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Notifications", description = "User notifications and preferences")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "List user notifications")
    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> list(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(notificationService.listForUser(CurrentUser.id(principal), pageable));
    }

    @Operation(summary = "Get unread notification count")
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(Map.of("count", notificationService.getUnreadCount(CurrentUser.id(principal))));
    }

    @Operation(summary = "Mark a notification as read")
    @PostMapping("/{id}/read")
    public ResponseEntity<Object> markRead(@PathVariable UUID id,
                                          @AuthenticationPrincipal UserDetails principal) {
        notificationService.markRead(CurrentUser.id(principal), id);
        return ResponseEntity.ok(Map.of("message", "Notification marked as read"));
    }

    @Operation(summary = "Mark all notifications as read")
    @PostMapping("/read-all")
    public ResponseEntity<Object> markAllRead(@AuthenticationPrincipal UserDetails principal) {
        notificationService.markAllRead(CurrentUser.id(principal));
        return ResponseEntity.ok(Map.of("message", "All notifications marked as read"));
    }

    @Operation(summary = "Get notification preferences")
    @GetMapping("/preferences")
    public ResponseEntity<List<NotificationPreferenceResponse>> getPreferences(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(notificationService.getPreferences(CurrentUser.id(principal)));
    }

    @Operation(summary = "Update notification preference")
    @PutMapping("/preferences")
    public ResponseEntity<NotificationPreferenceResponse> updatePreference(
            @Valid @RequestBody UpdatePreferenceRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(notificationService.updatePreference(CurrentUser.id(principal), req));
    }

}
