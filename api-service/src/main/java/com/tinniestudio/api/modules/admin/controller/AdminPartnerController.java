package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.partner.dto.AdminUpdatePartnerProfileRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerProfileResponse;
import com.tinniestudio.api.modules.partner.service.PartnerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin - Partners", description = "Admin management of existing partner profiles")
@RestController
@RequestMapping("/admin/partners")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminPartnerController {

    private final PartnerService partnerService;

    @Operation(summary = "Get a partner's profile by user id")
    @GetMapping("/{userId}")
    public ResponseEntity<PartnerProfileResponse> getProfile(@PathVariable UUID userId) {
        return ResponseEntity.ok(partnerService.getProfile(userId));
    }

    @Operation(summary = "Update a partner's profile (e.g. un-verify a partner that violates regulation, adjust revenue share)")
    @PatchMapping("/{userId}")
    public ResponseEntity<PartnerProfileResponse> updateProfile(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminUpdatePartnerProfileRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(
            partnerService.adminUpdateProfile(userId, req, UUID.fromString(principal.getUsername()))
        );
    }
}
