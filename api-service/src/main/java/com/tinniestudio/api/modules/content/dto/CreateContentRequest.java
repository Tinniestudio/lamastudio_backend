package com.tinniestudio.api.modules.content.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateContentRequest(
    @NotBlank String title,
    @NotNull ContentType type,
    MaturityRating maturityRating,
    String description,
    String shortDescription,
    LocalDate releaseDate,
    Boolean comingSoon,
    Integer durationSeconds,
    List<UUID> categoryIds
) {}
