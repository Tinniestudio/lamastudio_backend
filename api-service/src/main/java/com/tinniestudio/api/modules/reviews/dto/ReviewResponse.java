package com.tinniestudio.api.modules.reviews.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tinniestudio.api.shared.entity.ContentReview;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewResponse(
    UUID id, UUID contentId, UUID userId,
    Short rating, String body, String status,
    Instant createdAt, Instant updatedAt
) {
    public static ReviewResponse from(ContentReview r) {
        return new ReviewResponse(
            r.getId(), r.getContentId(), r.getUserId(),
            r.getRating(), r.getBody(), r.getStatus().name(),
            r.getCreatedAt(), r.getUpdatedAt()
        );
    }
}
