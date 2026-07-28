package com.tinniestudio.api.modules.notification.controller;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.service.NotificationTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Admin - Notification Templates")
@RestController
@RequestMapping("/admin/notification-templates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationTemplateController {

    private final NotificationTemplateService templateService;

    @Operation(summary = "List all notification templates")
    @GetMapping
    public ResponseEntity<List<NotificationTemplateResponse>> list() {
        return ResponseEntity.ok(templateService.list());
    }

    @Operation(summary = "Create notification template")
    @PostMapping
    public ResponseEntity<NotificationTemplateResponse> create(
            @Valid @RequestBody CreateNotificationTemplateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(req));
    }

    @Operation(summary = "Update notification template")
    @PatchMapping("/{id}")
    public ResponseEntity<NotificationTemplateResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateNotificationTemplateRequest req) {
        return ResponseEntity.ok(templateService.update(id, req));
    }

    @Operation(summary = "Delete notification template")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
