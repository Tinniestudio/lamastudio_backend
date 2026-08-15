package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import com.tinniestudio.api.modules.admin.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin - Audit Logs", description = "Query admin audit logs")
@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @Operation(summary = "List all audit logs")
    @GetMapping
    public ResponseEntity<Page<AuditLogResponse>> listAll(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(auditLogService.listAll(pageable));
    }

    @Operation(summary = "List audit logs for a specific target")
    @GetMapping("/target/{targetType}/{targetId}")
    public ResponseEntity<Page<AuditLogResponse>> listByTarget(
            @PathVariable String targetType,
            @PathVariable UUID targetId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(auditLogService.listByTarget(targetType, targetId, pageable));
    }
}
