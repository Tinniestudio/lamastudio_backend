package com.tinniestudio.api.modules.content.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UpdateContentRequest(
    String title, String description, String shortDescription,
    MaturityRating maturityRating, LocalDate releaseDate,
    String language, String country, Boolean comingSoon,
    Integer durationSeconds, String posterUrl, String thumbnailUrl,
    List<UUID> categoryIds
) {}
