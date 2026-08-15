package com.tinniestudio.api.modules.reviews.service;

import com.tinniestudio.api.modules.reviews.dto.CreateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewStatusRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ReviewService {
    Page<ReviewResponse> list(UUID contentId, Pageable pageable);
    ReviewResponse create(UUID userId, UUID contentId, CreateReviewRequest request);
    ReviewResponse update(UUID userId, UUID reviewId, UpdateReviewRequest request);
    void delete(UUID userId, UUID reviewId);
    ReviewResponse moderateStatus(UUID reviewId, UpdateReviewStatusRequest request);
}
