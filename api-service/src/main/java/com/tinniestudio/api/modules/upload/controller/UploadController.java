package com.tinniestudio.api.modules.upload.controller;

import com.tinniestudio.api.modules.upload.dto.CompleteUploadResponse;
import com.tinniestudio.api.modules.upload.dto.CreateUploadSessionRequest;
import com.tinniestudio.api.modules.upload.dto.UploadSessionResponse;
import com.tinniestudio.api.modules.upload.dto.UploadStatusResponse;
import com.tinniestudio.api.modules.upload.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Uploads", description = "Presigned upload session management")
@RestController
@RequestMapping("/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    @Operation(summary = "Create a presigned upload session — returns a URL for direct-to-bucket upload")
    @PostMapping("/sessions")
    public ResponseEntity<UploadSessionResponse> createSession(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody CreateUploadSessionRequest req) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(uploadService.createSession(userId, req));
    }

    @Operation(summary = "Complete an upload session — verifies object exists in storage, triggers processing if applicable")
    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<CompleteUploadResponse> complete(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID sessionId) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(uploadService.completeSession(userId, sessionId));
    }

    @Operation(summary = "Get upload session status and video processing progress")
    @GetMapping("/{sessionId}/status")
    public ResponseEntity<UploadStatusResponse> status(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID sessionId) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(uploadService.getStatus(userId, sessionId));
    }
}
