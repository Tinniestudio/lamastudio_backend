package com.tinniestudio.api.modules.partner.controller;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.service.PartnerApplicationService;
import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.partner.dto.*;
import com.tinniestudio.api.modules.partner.service.PartnerService;
import com.tinniestudio.api.shared.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
// NOTE: io.swagger.v3.oas.annotations.parameters.RequestBody is deliberately NOT imported by
// simple name — it collides with org.springframework.web.bind.annotation.RequestBody (used on
// apply()/updateProfile()'s JSON body parameters below) and an explicit import would shadow the
// wildcard import, silently breaking Spring's body binding for those methods. Used
// fully-qualified at the uploadLogo() call site instead.
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Tag(name = "Partner Portal", description = "Endpoints for users with the PARTNER role")
@RestController
@RequestMapping("/partners")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PARTNER')")
public class PartnerController {

    private final PartnerService partnerService;
    private final PartnerApplicationService applicationService;
    private final ContentRepository contentRepository;

    @Operation(summary = "Apply to become a partner")
    @PreAuthorize("isAuthenticated()")
    @RateLimit(maxRequests = 3, windowMinutes = 60, keyStrategy = "USER_OR_IP",
               errorMessage = "Too many applications. Please try again later.")
    @PostMapping("/applications")
    public ResponseEntity<PartnerApplicationResponse> apply(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody PartnerApplicationRequest req) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(applicationService.apply(userId, req));
    }

    @Operation(summary = "Get own partner profile")
    @GetMapping("/profile")
    public ResponseEntity<PartnerProfileResponse> getProfile(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(partnerService.getProfile(userId));
    }

    @Operation(summary = "Update own partner profile")
    @PatchMapping("/profile")
    public ResponseEntity<PartnerProfileResponse> updateProfile(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody UpdatePartnerProfileRequest req) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(partnerService.updateProfile(userId, req));
    }

    // Explicit consumes + a Swagger-only "shadow" DTO for the multipart schema: without either,
    // springdoc has previously defaulted this operation's documented request body to a plain
    // JSON object in Swagger UI (no file picker), which both hides the real multipart contract
    // from API consumers and encourages hand-rolled curl reproductions that mis-set the file
    // part's Content-Type — see AdminCategoryController's multipart overloads for the same
    // pattern applied to a request-JSON-part + file combination.
    @Operation(summary = "Upload partner logo")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true,
        content = @Content(
            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            schema = @Schema(implementation = LogoUploadForm.class)
        )
    )
    @PostMapping(value = "/profile/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PartnerProfileResponse> uploadLogo(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam("file") MultipartFile file) throws IOException {
        UUID userId = UUID.fromString(principal.getUsername());
        partnerService.uploadLogo(userId, file);
        return ResponseEntity.ok(partnerService.getProfile(userId));
    }

    @Operation(summary = "Get partner dashboard statistics")
    @GetMapping("/dashboard")
    public ResponseEntity<PartnerDashboardResponse> getDashboard(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(partnerService.getDashboard(userId));
    }

    @Operation(summary = "Get own upload sessions")
    @GetMapping("/uploads")
    public ResponseEntity<Page<PartnerUploadSummaryResponse>> getUploads(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(partnerService.getUploads(userId, pageable));
    }

    @Operation(summary = "Get own content")
    @GetMapping("/contents")
    public ResponseEntity<Page<ContentResponse>> getContents(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(
            contentRepository.findByCreatedByOrderByCreatedAtDesc(userId, pageable).map(ContentResponse::from)
        );
    }
}
