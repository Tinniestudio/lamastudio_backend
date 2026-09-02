package com.tinniestudio.api.modules.content.dto;

import com.tinniestudio.api.shared.entity.Content;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ContentSummaryResponse(
    UUID id, String title, String slug, String shortDescription,
    com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse contentType,
    String status, String maturityRating,
    LocalDate releaseDate, Boolean featured, Boolean comingSoon,
    Long viewCount, BigDecimal averageRating, Integer reviewCount,
    String posterUrl, String thumbnailUrl
) {
    public static ContentSummaryResponse from(Content c) {
        return new ContentSummaryResponse(
            c.getId(), c.getTitle(), c.getSlug(), c.getShortDescription(),
            com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse.from(c.getContentType()),
            c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getFeatured(), c.getComingSoon(),
            c.getViewCount(), c.getAverageRating(), c.getReviewCount(),
            c.getPosterUrl(), c.getThumbnailUrl()
        );
    }
}
