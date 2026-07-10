package com.tinniestudio.api.modules.episode.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record UpdateEpisodeRequest(
    @NotBlank String title,
    String description,
    LocalDate releaseDate,
    Integer durationSeconds,
    String thumbnailUrl
) {}
