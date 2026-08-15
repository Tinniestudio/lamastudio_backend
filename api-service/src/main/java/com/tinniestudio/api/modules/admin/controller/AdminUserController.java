package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.admin.dto.AdminUserResponse;
import com.tinniestudio.api.modules.admin.dto.PromoteToPartnerRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.modules.admin.service.AdminUserService;
import com.tinniestudio.api.modules.partner.dto.PartnerProfileResponse;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
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

import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin - Users", description = "Admin user management")
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "List all users with optional filters")
    @GetMapping
    public ResponseEntity<Page<AdminUserResponse>> listUsers(
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminUserService.listUsers(status, search, pageable));
    }

    @Operation(summary = "Get user by ID")
    @GetMapping("/{id}")
    public ResponseEntity<AdminUserResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(adminUserService.getById(id));
    }

    @Operation(summary = "Update user account fields")
    @PatchMapping("/{id}")
    public ResponseEntity<AdminUserResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest req) {
        return ResponseEntity.ok(adminUserService.update(id, req));
    }

    @Operation(summary = "Update user account status")
    @PatchMapping("/{id}/status")
    public ResponseEntity<Object> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserStatusRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        adminUserService.updateStatus(id, req, CurrentUser.id(principal));
        return ResponseEntity.ok(Map.of("message", "User status updated successfully"));
    }

    @Operation(summary = "Soft-delete a user account")
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        adminUserService.softDelete(id, CurrentUser.id(principal));
        return ResponseEntity.ok(Map.of("message", "User deleted successfully"));
    }

    @Operation(summary = "Directly promote a user to partner (no application required)")
    @PostMapping("/{id}/promote-to-partner")
    public ResponseEntity<PartnerProfileResponse> promoteToPartner(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) PromoteToPartnerRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(
            adminUserService.promoteToPartner(id, req, CurrentUser.id(principal))
        );
    }
}
