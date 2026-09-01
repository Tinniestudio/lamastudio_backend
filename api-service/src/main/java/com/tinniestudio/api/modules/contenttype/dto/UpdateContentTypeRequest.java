package com.tinniestudio.api.modules.contenttype.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind;
import jakarta.validation.constraints.Size;

public record UpdateContentTypeRequest(
    @Size(max = 100) String name,
    String description,
    StructuralKind structuralKind,
    Integer displayOrder,
    Boolean isActive
) {}
