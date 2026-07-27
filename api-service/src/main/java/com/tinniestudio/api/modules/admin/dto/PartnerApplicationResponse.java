package com.tinniestudio.api.modules.admin.dto;

import com.tinniestudio.api.shared.entity.PartnerApplication;
import java.time.Instant;
import java.util.UUID;

public record PartnerApplicationResponse(
    UUID id,
    UUID userId,
    String companyName,
    String description,
    String websiteUrl,
    String status,
    String rejectionReason,
    UUID reviewedBy,
    Instant reviewedAt,
    Instant createdAt
) {
    public static PartnerApplicationResponse from(PartnerApplication a) {
        return new PartnerApplicationResponse(
            a.getId(), a.getUserId(), a.getCompanyName(), a.getDescription(),
            a.getWebsiteUrl(), a.getStatus().name(), a.getRejectionReason(),
            a.getReviewedBy(), a.getReviewedAt(), a.getCreatedAt()
        );
    }
}
