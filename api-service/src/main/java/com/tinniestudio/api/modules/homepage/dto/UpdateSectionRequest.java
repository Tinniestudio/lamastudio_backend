package com.tinniestudio.api.modules.homepage.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.SectionType;
import java.util.UUID;

public record UpdateSectionRequest(
    String title,
    SectionType sectionType,
    UUID categoryId,
    Integer displayOrder,
    Boolean isActive
) {}
