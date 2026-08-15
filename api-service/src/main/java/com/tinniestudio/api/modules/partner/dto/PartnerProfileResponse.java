package com.tinniestudio.api.modules.partner.dto;

import com.tinniestudio.api.shared.entity.PartnerProfile;
import java.math.BigDecimal;
import java.util.UUID;

public record PartnerProfileResponse(
    UUID id,
    UUID userId,
    String companyName,
    String websiteUrl,
    String bio,
    String logoUrl,
    BigDecimal revenueSharePercentage,
    Boolean isVerified
) {
    public static PartnerProfileResponse from(PartnerProfile p) {
        return new PartnerProfileResponse(
            p.getId(), p.getUserId(), p.getCompanyName(), p.getWebsiteUrl(),
            p.getBio(), p.getLogoUrl(), p.getRevenueSharePercentage(), p.getIsVerified()
        );
    }
}
