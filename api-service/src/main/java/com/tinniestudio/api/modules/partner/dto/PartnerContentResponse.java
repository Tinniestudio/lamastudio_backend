package com.tinniestudio.api.modules.partner.dto;

import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.shared.entity.Content;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Partner-facing content view — deliberately a distinct type from
 * {@link com.tinniestudio.api.modules.content.dto.ContentResponse} (CLAUDE.md Batch 13 #10), even
 * though the field set is identical today, so an admin-only field added to ContentResponse later
 * (revenue, cost, internal moderation flags) doesn't automatically reach partners just because
 * both types happen to read from the same Content entity.
 */
public record PartnerContentResponse(
    UUID id, String title, String slug, String description, String shortDescription,
    String type, String status, String maturityRating,
    LocalDate releaseDate, String language, String country,
    Boolean featured, Boolean comingSoon, Long viewCount,
    Integer durationSeconds, String posterUrl, String thumbnailUrl,
    BigDecimal averageRating, Integer reviewCount,
    List<String> categoryNames, Instant publishedAt, String rejectionReason
) {
    public static PartnerContentResponse from(Content c) {
        return new PartnerContentResponse(
            c.getId(), c.getTitle(), c.getSlug(),
            c.getDescription(), c.getShortDescription(),
            c.getType().name(), c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getLanguage(), c.getCountry(),
            c.getFeatured(), c.getComingSoon(), c.getViewCount(),
            c.getDurationSeconds(), c.getPosterUrl(), c.getThumbnailUrl(),
            c.getAverageRating(), c.getReviewCount(),
            c.getCategories().stream().map(cat -> cat.getName()).toList(),
            c.getPublishedAt(), c.getRejectionReason()
        );
    }

    /** For create/update, which go through ContentService and get back a ContentResponse. */
    public static PartnerContentResponse from(ContentResponse c) {
        return new PartnerContentResponse(
            c.id(), c.title(), c.slug(), c.description(), c.shortDescription(),
            c.type(), c.status(), c.maturityRating(),
            c.releaseDate(), c.language(), c.country(),
            c.featured(), c.comingSoon(), c.viewCount(),
            c.durationSeconds(), c.posterUrl(), c.thumbnailUrl(),
            c.averageRating(), c.reviewCount(),
            c.categoryNames(), c.publishedAt(), c.rejectionReason()
        );
    }
}
