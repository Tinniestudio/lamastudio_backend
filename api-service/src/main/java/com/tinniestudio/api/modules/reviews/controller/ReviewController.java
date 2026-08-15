package com.tinniestudio.api.modules.reviews.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.reviews.dto.CreateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewRequest;
import com.tinniestudio.api.modules.reviews.service.ReviewService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Reviews", description = "Content reviews management")
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "List reviews for a content item (public)")
    @GetMapping("/contents/{contentId}/reviews")
    public ResponseEntity<Page<ReviewResponse>> list(
            @PathVariable UUID contentId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(reviewService.list(contentId, pageable));
    }

    @Operation(summary = "Create a review for a content item")
    @RateLimit(maxRequests = 10, windowMinutes = 60, keyStrategy = "USER_OR_IP")
    @PostMapping("/contents/{contentId}/reviews")
    public ResponseEntity<ReviewResponse> create(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId,
            @Valid @RequestBody CreateReviewRequest request) {
        ReviewResponse response = reviewService.create(CurrentUser.id(principal), contentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Update an existing review")
    @PatchMapping("/reviews/{id}")
    public ResponseEntity<ReviewResponse> update(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateReviewRequest request) {
        return ResponseEntity.ok(reviewService.update(CurrentUser.id(principal), id, request));
    }

    @Operation(summary = "Delete a review")
    @DeleteMapping("/reviews/{id}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        reviewService.delete(CurrentUser.id(principal), id);
        return ResponseEntity.ok(Map.of("message", "Review deleted successfully"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

}
