package com.tinniestudio.api.modules.season.dto;

import java.time.LocalDate;

public record CreateSeasonRequest(
    Integer seasonNumber,
    String title,
    String description,
    LocalDate releaseDate,
    String posterUrl,
    String thumbnailUrl
) {}
