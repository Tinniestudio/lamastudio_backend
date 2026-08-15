package com.tinniestudio.api.modules.category.dto;

import jakarta.validation.constraints.Size;

public record UpdateCategoryRequest(
    @Size(max = 100) String name,
    String description,
    Integer displayOrder,
    Boolean isActive
) {}
