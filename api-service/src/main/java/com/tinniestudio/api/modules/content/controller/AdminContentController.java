package com.tinniestudio.api.modules.content.controller;

import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.modules.content.dto.CreateContentRequest;
import com.tinniestudio.api.modules.content.dto.UpdateContentRequest;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.content.service.ContentService;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
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

@Tag(name = "Admin - Content", description = "Manage content lifecycle")
@RestController
@RequestMapping("/admin/contents")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")
public class AdminContentController {

    private final ContentService contentService;
    private final ContentRepository contentRepository;

    @Operation(summary = "List all content across all statuses (admin view)")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<ContentResponse>> listAll(
            @RequestParam(required = false) ContentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<Content> page = (status != null)
            ? contentRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
            : contentRepository.findAllByOrderByCreatedAtDesc(pageable);
        return ResponseEntity.ok(page.map(ContentResponse::from));
    }

    @Operation(summary = "Create new content (starts in DRAFT)")
    @PostMapping
    public ResponseEntity<ContentResponse> create(
            @Valid @RequestBody CreateContentRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(contentService.create(req, UUID.fromString(principal.getUsername())));
    }

    @Operation(summary = "Update content metadata")
    @PatchMapping("/{id}")
    public ResponseEntity<ContentResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateContentRequest req) {
        return ResponseEntity.ok(contentService.update(id, req));
    }

    @Operation(summary = "Delete content")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        contentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Submit for review (DRAFT → REVIEW)")
    @PostMapping("/{id}/submit")
    public ResponseEntity<ContentResponse> submit(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.transitionStatus(id, ContentStatus.REVIEW));
    }

    @Operation(summary = "Approve for processing (REVIEW → PROCESSING)")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ContentResponse> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.transitionStatus(id, ContentStatus.PROCESSING));
    }

    @Operation(summary = "Reject content (REVIEW → REJECTED)")
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ContentResponse> reject(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.transitionStatus(id, ContentStatus.REJECTED));
    }

    @Operation(summary = "Publish content (PROCESSING → PUBLISHED)")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ContentResponse> publish(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.transitionStatus(id, ContentStatus.PUBLISHED));
    }

    @Operation(summary = "Archive content")
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ContentResponse> archive(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.transitionStatus(id, ContentStatus.ARCHIVED));
    }

    @Operation(summary = "Toggle featured flag")
    @PatchMapping("/{id}/feature")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ContentResponse> toggleFeatured(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.toggleFeatured(id));
    }
}
