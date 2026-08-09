package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.admin.dto.RejectAppealRequest;
import com.tinniestudio.api.modules.appeal.dto.AppealResponse;
import com.tinniestudio.api.modules.appeal.service.AppealService;
import com.tinniestudio.api.shared.entity.DomainEnums.AppealStatus;
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

import java.util.UUID;

@Tag(name = "Admin - Appeals", description = "Review suspended-account appeals")
@RestController
@RequestMapping("/admin/appeals")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAppealController {

    private final AppealService appealService;

    @Operation(summary = "List account appeals with optional status filter")
    @GetMapping
    public ResponseEntity<Page<AppealResponse>> list(
            @RequestParam(required = false) AppealStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(appealService.list(status, pageable));
    }

    @Operation(summary = "Approve an appeal (reactivates the account)")
    @PostMapping("/{id}/approve")
    public ResponseEntity<AppealResponse> approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(appealService.approve(id, UUID.fromString(principal.getUsername())));
    }

    @Operation(summary = "Reject an appeal")
    @PostMapping("/{id}/reject")
    public ResponseEntity<AppealResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectAppealRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(appealService.reject(id, req, UUID.fromString(principal.getUsername())));
    }
}
