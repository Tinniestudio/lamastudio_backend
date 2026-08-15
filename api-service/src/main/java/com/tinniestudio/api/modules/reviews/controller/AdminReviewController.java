package com.tinniestudio.api.modules.reviews.controller;

import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewStatusRequest;
import com.tinniestudio.api.modules.reviews.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin - Reviews", description = "Manage review moderation")
@RestController
@RequestMapping("/admin/reviews")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "Moderate a review status (approve/reject/pending)")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ReviewResponse> moderateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateReviewStatusRequest request) {
        return ResponseEntity.ok(reviewService.moderateStatus(id, request));
    }
}
