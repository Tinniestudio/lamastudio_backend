package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.admin.service.PartnerApplicationService;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
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

@Tag(name = "Admin - Partner Applications", description = "Manage partner applications")
@RestController
@RequestMapping("/admin/partner-applications")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminPartnerApplicationController {

    private final PartnerApplicationService applicationService;

    @Operation(summary = "List partner applications with optional status filter")
    @GetMapping
    public ResponseEntity<Page<PartnerApplicationResponse>> list(
            @RequestParam(required = false) PartnerApplicationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(applicationService.list(status, pageable));
    }

    @Operation(summary = "Approve a partner application")
    @PostMapping("/{id}/approve")
    public ResponseEntity<PartnerApplicationResponse> approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(applicationService.approve(id, CurrentUser.id(principal)));
    }

    @Operation(summary = "Reject a partner application")
    @PostMapping("/{id}/reject")
    public ResponseEntity<PartnerApplicationResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectApplicationRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(applicationService.reject(id, req, CurrentUser.id(principal)));
    }
}
