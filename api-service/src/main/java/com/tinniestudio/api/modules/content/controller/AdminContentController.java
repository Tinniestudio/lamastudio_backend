package com.tinniestudio.api.modules.content.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.modules.content.dto.CreateContentRequest;
import com.tinniestudio.api.modules.content.dto.RejectContentRequest;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
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
            .body(contentService.create(req, CurrentUser.id(principal)));
    }

    @Operation(summary = "Update content metadata")
    @PatchMapping("/{id}")
    public ResponseEntity<ContentResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateContentRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        assertOwnedByCallerOrAdmin(id, principal);
        return ResponseEntity.ok(contentService.update(id, req));
    }

    @Operation(summary = "Delete content")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> delete(@PathVariable UUID id) {
        contentService.delete(id);
        return ResponseEntity.ok(Map.of("message", "Content deleted successfully"));
    }

    @Operation(summary = "Submit for review — DRAFT or REJECTED to REVIEW (resubmit clears any prior rejectionReason)")
    @PostMapping("/{id}/submit")
    public ResponseEntity<ContentResponse> submit(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        assertOwnedByCallerOrAdmin(id, principal);
        return ResponseEntity.ok(contentService.transitionStatus(id, ContentStatus.REVIEW, null));
    }

    @Operation(summary = "Approve for processing (REVIEW → PROCESSING)")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ContentResponse> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.transitionStatus(id, ContentStatus.PROCESSING, null));
    }

    @Operation(summary = "Reject content (REVIEW → REJECTED)")
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ContentResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectContentRequest req) {
        return ResponseEntity.ok(contentService.transitionStatus(id, ContentStatus.REJECTED, req.getReason()));
    }

    @Operation(summary = "Publish content (PROCESSING → PUBLISHED)")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ContentResponse> publish(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.transitionStatus(id, ContentStatus.PUBLISHED, null));
    }

    @Operation(summary = "Archive content")
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ContentResponse> archive(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.transitionStatus(id, ContentStatus.ARCHIVED, null));
    }

    @Operation(summary = "Toggle featured flag")
    @PatchMapping("/{id}/feature")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ContentResponse> toggleFeatured(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.toggleFeatured(id));
    }

    /**
     * update()/submit() are open to both ADMIN and PARTNER at the class level, but a partner
     * must only be able to touch their own content — otherwise any partner can edit or resubmit
     * any other partner's content by id. Admins bypass this check entirely.
     */
    private void assertOwnedByCallerOrAdmin(UUID contentId, UserDetails principal) {
        if (CurrentUser.isAdmin(principal)) {
            return;
        }
        Content content = contentRepository.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId));
        UUID callerId = CurrentUser.id(principal);
        if (!callerId.equals(content.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId);
        }
    }
}
