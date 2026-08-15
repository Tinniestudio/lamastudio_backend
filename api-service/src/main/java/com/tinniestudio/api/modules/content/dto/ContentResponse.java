package com.tinniestudio.api.modules.content.dto;

import com.tinniestudio.api.shared.entity.Content;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ContentResponse(
    UUID id, String title, String slug, String description, String shortDescription,
    String type, String status, String maturityRating,
    LocalDate releaseDate, String language, String country,
    Boolean featured, Boolean comingSoon, Long viewCount,
    Integer durationSeconds, String posterUrl, String thumbnailUrl,
    BigDecimal averageRating, Integer reviewCount,
    List<String> categoryNames, Instant publishedAt
) {
    public static ContentResponse from(Content c) {
        return new ContentResponse(
            c.getId(), c.getTitle(), c.getSlug(),
            c.getDescription(), c.getShortDescription(),
            c.getType().name(), c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getLanguage(), c.getCountry(),
            c.getFeatured(), c.getComingSoon(), c.getViewCount(),
            c.getDurationSeconds(), c.getPosterUrl(), c.getThumbnailUrl(),
            c.getAverageRating(), c.getReviewCount(),
            c.getCategories().stream().map(cat -> cat.getName()).toList(),
            c.getPublishedAt()
        );
    }
}
