package com.tinniestudio.api.modules.episode.dto;

import java.time.LocalDate;

public record UpdateEpisodeRequest(
    String title,
    String description,
    LocalDate releaseDate,
    Integer durationSeconds,
    String thumbnailUrl
) {}
