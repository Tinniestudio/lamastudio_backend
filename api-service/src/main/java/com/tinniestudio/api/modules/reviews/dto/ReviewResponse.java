package com.tinniestudio.api.modules.reviews.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tinniestudio.api.shared.entity.ContentReview;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewResponse(
    UUID id, UUID contentId, UUID userId,
    Short rating, String body, String status,
    Instant createdAt, Instant updatedAt,
    ReviewAuthorResponse author
) {
    /** Used by create/update/moderateStatus/getMine — author is always null; see the two-arg overload for list(). */
    public static ReviewResponse from(ContentReview r) {
        return from(r, null);
    }

    public static ReviewResponse from(ContentReview r, ReviewAuthorResponse author) {
        return new ReviewResponse(
            r.getId(), r.getContentId(), r.getUserId(),
            r.getRating(), r.getBody(), r.getStatus().name(),
            r.getCreatedAt(), r.getUpdatedAt(),
            author
        );
    }
}
