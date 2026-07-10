package com.tinniestudio.api.modules.category.dto;

import com.tinniestudio.api.shared.entity.Category;
import java.util.UUID;

public record CategoryResponse(
    UUID id,
    String name,
    String slug,
    String description,
    String posterUrl,
    Integer displayOrder,
    Boolean isActive
) {
    public static CategoryResponse from(Category c) {
        return new CategoryResponse(
            c.getId(), c.getName(), c.getSlug(),
            c.getDescription(), c.getPosterUrl(),
            c.getDisplayOrder(), c.getIsActive()
        );
    }
}
