package com.tinniestudio.api.modules.content.dto;

import com.tinniestudio.api.shared.entity.Content;
import java.time.LocalDate;
import java.util.UUID;

public record ContentSummaryResponse(
    UUID id, String title, String slug, String shortDescription,
    String type, String status, String maturityRating,
    LocalDate releaseDate, Boolean featured, Boolean comingSoon,
    Long viewCount, String posterUrl, String thumbnailUrl
) {
    public static ContentSummaryResponse from(Content c) {
        return new ContentSummaryResponse(
            c.getId(), c.getTitle(), c.getSlug(), c.getShortDescription(),
            c.getType().name(), c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getFeatured(), c.getComingSoon(),
            c.getViewCount(), c.getPosterUrl(), c.getThumbnailUrl()
        );
    }
}
