package com.tinniestudio.api.modules.homepage.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.SectionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateSectionRequest(
    @NotBlank String title,
    @NotNull SectionType sectionType,
    UUID categoryId,
    Integer displayOrder
) {}
