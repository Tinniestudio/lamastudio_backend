package com.tinniestudio.api.modules.contenttype.dto;

import com.tinniestudio.api.shared.entity.ContentType;
import java.util.UUID;

public record ContentTypeResponse(
    UUID id,
    String name,
    String slug,
    String structuralKind,
    Integer displayOrder,
    Boolean isActive
) {
    public static ContentTypeResponse from(ContentType t) {
        return new ContentTypeResponse(
            t.getId(), t.getName(), t.getSlug(),
            t.getStructuralKind().name(),
            t.getDisplayOrder(), t.getIsActive()
        );
    }
}
