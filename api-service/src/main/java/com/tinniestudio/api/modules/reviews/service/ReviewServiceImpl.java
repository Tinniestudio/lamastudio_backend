package com.tinniestudio.api.modules.reviews.service;

import com.tinniestudio.api.modules.reviews.dto.CreateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewStatusRequest;
import com.tinniestudio.api.modules.reviews.repository.ReviewRepository;
import com.tinniestudio.api.shared.entity.ContentReview;
import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepo;

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewResponse> list(UUID contentId, Pageable pageable) {
        return reviewRepo
                .findByContentIdAndStatusOrderByCreatedAtDesc(contentId, ReviewStatus.APPROVED, pageable)
                .map(ReviewResponse::from);
    }

    @Override
    @Transactional
    public ReviewResponse create(UUID userId, UUID contentId, CreateReviewRequest request) {
        if (reviewRepo.existsByUserIdAndContentId(userId, contentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You have already reviewed this content");
        }

        ContentReview review = new ContentReview();
        review.setUserId(userId);
        review.setContentId(contentId);
        review.setRating(request.getRating());
        review.setBody(request.getBody());
        review.setStatus(ReviewStatus.APPROVED);

        return ReviewResponse.from(reviewRepo.save(review));
    }

    @Override
    @Transactional
    public ReviewResponse update(UUID userId, UUID reviewId, UpdateReviewRequest request) {
        ContentReview review = reviewRepo.findByIdAndUserId(reviewId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Review not found or not owned by user"));

        if (request.getRating() != null) {
            review.setRating(request.getRating());
        }
        if (request.getBody() != null) {
            review.setBody(request.getBody());
        }

        return ReviewResponse.from(reviewRepo.save(review));
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID reviewId) {
        ContentReview review = reviewRepo.findByIdAndUserId(reviewId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Review not found or not owned by user"));
        reviewRepo.delete(review);
    }

    @Override
    @Transactional
    public ReviewResponse moderateStatus(UUID reviewId, UpdateReviewStatusRequest request) {
        ContentReview review = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Review not found"));

        review.setStatus(request.getStatus());

        return ReviewResponse.from(reviewRepo.save(review));
    }
}
