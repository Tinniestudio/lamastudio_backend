package com.tinniestudio.api.modules.upload.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.upload.dto.CompleteUploadRequest;
import com.tinniestudio.api.modules.upload.dto.CompleteUploadResponse;
import com.tinniestudio.api.modules.upload.dto.CreateUploadSessionRequest;
import com.tinniestudio.api.modules.upload.dto.PartUploadUrlResponse;
import com.tinniestudio.api.modules.upload.dto.UploadedPartResponse;
import com.tinniestudio.api.modules.upload.dto.UploadSessionResponse;
import com.tinniestudio.api.modules.upload.dto.UploadStatusResponse;
import com.tinniestudio.api.modules.upload.service.UploadService;
import com.tinniestudio.api.shared.entity.DomainEnums.TargetEntityType;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadType;
import com.tinniestudio.api.shared.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Uploads", description = "Presigned upload session management")
@RestController
@RequestMapping("/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    @Operation(summary = "Create a presigned upload session — returns a URL for direct-to-bucket upload")
    @RateLimit(maxRequests = 10, windowMinutes = 15, keyStrategy = "USER_OR_IP",
               errorMessage = "Too many upload requests. Please wait before trying again.")
    @PostMapping("/sessions")
    public ResponseEntity<UploadSessionResponse> createSession(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody CreateUploadSessionRequest req) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(uploadService.createSession(userId, req));
    }

    @Operation(summary = "Complete an upload session — verifies object exists in storage, triggers processing if applicable",
        description = "Body is only required for SUBTITLE uploads (languageCode, optional label/isDefault); omit for every other upload type.")
    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<CompleteUploadResponse> complete(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID sessionId,
            @RequestBody(required = false) CompleteUploadRequest body) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(uploadService.completeSession(userId, sessionId, body));
    }

    @Operation(summary = "Get a freshly-signed presigned PUT URL for one part of an in-progress multipart upload")
    @GetMapping("/{sessionId}/parts/{partNumber}/url")
    public ResponseEntity<PartUploadUrlResponse> getPartUploadUrl(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID sessionId,
            @PathVariable int partNumber) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(uploadService.getPartUploadUrl(userId, sessionId, partNumber));
    }

    @Operation(summary = "List parts already uploaded for a multipart session — the resume mechanism")
    @GetMapping("/{sessionId}/parts")
    public ResponseEntity<List<UploadedPartResponse>> listUploadedParts(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID sessionId) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(uploadService.listUploadedParts(userId, sessionId));
    }

    @Operation(summary = "Find the caller's own active (PENDING, non-expired) upload session for a given target+type, if one exists")
    @GetMapping("/sessions/active")
    public ResponseEntity<?> findActiveSession(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam TargetEntityType targetEntityType,
            @RequestParam UUID targetEntityId,
            @RequestParam UploadType uploadType) {
        UUID userId = CurrentUser.id(principal);
        return uploadService.findActiveSession(userId, targetEntityType, targetEntityId, uploadType)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Get upload session status and video processing progress")
    @GetMapping("/{sessionId}/status")
    public ResponseEntity<UploadStatusResponse> status(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID sessionId) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(uploadService.getStatus(userId, sessionId));
    }
}
