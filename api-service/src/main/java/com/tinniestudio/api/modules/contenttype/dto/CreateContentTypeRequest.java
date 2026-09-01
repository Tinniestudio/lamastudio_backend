package com.tinniestudio.api.modules.contenttype.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateContentTypeRequest(
    @NotBlank @Size(max = 100) String name,
    String description,
    @NotNull StructuralKind structuralKind,
    Integer displayOrder
) {}
