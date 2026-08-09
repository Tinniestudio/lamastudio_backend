package com.tinniestudio.api.modules.content.controller;

import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.service.ContentService;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import com.tinniestudio.api.shared.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Content", description = "Browse and discover content")
@RestController
@RequestMapping("/contents")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @Operation(summary = "List published content with optional filters")
    @RateLimit(maxRequests = 60, windowMinutes = 1, keyStrategy = "IP_ONLY")
    @GetMapping
    public ResponseEntity<Page<ContentSummaryResponse>> list(
            @RequestParam(required = false) ContentType type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) MaturityRating maturityRating,
            @RequestParam(required = false) Boolean comingSoon,
            @PageableDefault(size = 20, sort = "publishedAt") Pageable pageable) {
        return ResponseEntity.ok(contentService.list(type, category, maturityRating, comingSoon, pageable));
    }

    @Operation(summary = "Get content by slug")
    @GetMapping("/{slug}")
    public ResponseEntity<ContentResponse> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(contentService.getBySlug(slug));
    }

    @Operation(summary = "Get content by id")
    @GetMapping("/id/{id}")
    public ResponseEntity<ContentResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.getById(id));
    }

    @Operation(summary = "Record a view event for content — public, works for anonymous viewers " +
            "(Batch 16 #7: anonymous views are tracked with userId=null)")
    @RateLimit(maxRequests = 30, windowMinutes = 1, keyStrategy = "USER_OR_IP")
    @PostMapping("/id/{id}/view")
    public ResponseEntity<Void> recordView(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = principal != null ? UUID.fromString(principal.getUsername()) : null;
        contentService.recordView(id, userId);
        return ResponseEntity.accepted().build();
    }
}
