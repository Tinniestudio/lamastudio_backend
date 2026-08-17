package com.tinniestudio.api.modules.partner.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.service.PartnerApplicationService;
import com.tinniestudio.api.modules.content.dto.CreateContentRequest;
import com.tinniestudio.api.modules.content.dto.UpdateContentRequest;
import com.tinniestudio.api.modules.partner.dto.*;
import com.tinniestudio.api.modules.partner.service.PartnerService;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Partner Portal", description = "Endpoints for users with the PARTNER role")
@RestController
@RequestMapping("/partners")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PARTNER')")
public class PartnerController {

    private final PartnerService partnerService;
    private final PartnerApplicationService applicationService;

    @Operation(summary = "Apply to become a partner")
    @PreAuthorize("isAuthenticated()")
    @RateLimit(maxRequests = 3, windowMinutes = 60, keyStrategy = "USER_OR_IP",
               errorMessage = "Too many applications. Please try again later.")
    @PostMapping("/applications")
    public ResponseEntity<PartnerApplicationResponse> apply(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody PartnerApplicationRequest req) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(applicationService.apply(userId, req));
    }

    @Operation(summary = "Get own partner profile")
    @GetMapping("/profile")
    public ResponseEntity<PartnerProfileResponse> getProfile(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(partnerService.getProfile(userId));
    }

    @Operation(summary = "Update own partner profile")
    @PatchMapping("/profile")
    public ResponseEntity<PartnerProfileResponse> updateProfile(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody UpdatePartnerProfileRequest req) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(partnerService.updateProfile(userId, req));
    }

    // Logo upload: no dedicated multipart endpoint — use the standard presigned-upload flow
    // (POST /uploads/sessions with uploadType=PARTNER_LOGO, then .../complete), then PATCH
    // /partners/profile with the resulting logoUrl, exactly like Content.posterUrl/thumbnailUrl.

    @Operation(summary = "Get partner dashboard statistics")
    @GetMapping("/dashboard")
    public ResponseEntity<PartnerDashboardResponse> getDashboard(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(partnerService.getDashboard(userId));
    }

    @Operation(summary = "Get own upload sessions")
    @GetMapping("/uploads")
    public ResponseEntity<Page<PartnerUploadSummaryResponse>> getUploads(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(partnerService.getUploads(userId, pageable));
    }

    @Operation(summary = "List own content — filter by status, search by title, sort via ?sort=field,dir")
    @GetMapping("/contents")
    public ResponseEntity<Page<PartnerContentResponse>> getContents(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(required = false) ContentStatus status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(partnerService.listContents(userId, status, q, pageable));
    }

    @Operation(summary = "Get one piece of own content (404 if not found or not owned)")
    @GetMapping("/contents/{id}")
    public ResponseEntity<PartnerContentResponse> getContent(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(partnerService.getContent(userId, id));
    }

    @Operation(summary = "Create new content (starts in DRAFT, owned by the calling partner)")
    @PostMapping("/contents")
    public ResponseEntity<PartnerContentResponse> createContent(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody CreateContentRequest req) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(partnerService.createContent(userId, req));
    }

    @Operation(summary = "Update own content metadata")
    @PatchMapping("/contents/{id}")
    public ResponseEntity<PartnerContentResponse> updateContent(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateContentRequest req) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(partnerService.updateContent(userId, id, req));
    }
}
