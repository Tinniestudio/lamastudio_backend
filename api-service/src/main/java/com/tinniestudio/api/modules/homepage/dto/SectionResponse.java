package com.tinniestudio.api.modules.homepage.dto;

import com.tinniestudio.api.shared.entity.HomepageSection;
import java.util.UUID;

public record SectionResponse(
    UUID id,
    String title,
    String sectionType,
    UUID categoryId,
    String categorySlug,
    Integer displayOrder,
    Boolean isActive
) {
    public static SectionResponse from(HomepageSection s) {
        return new SectionResponse(
            s.getId(), s.getTitle(), s.getSectionType().name(),
            s.getCategory() != null ? s.getCategory().getId() : null,
            s.getCategory() != null ? s.getCategory().getSlug() : null,
            s.getDisplayOrder(), s.getIsActive()
        );
    }
}
