package com.tinniestudio.api.modules.season.dto;

import java.time.LocalDate;

public record UpdateSeasonRequest(
    String title,
    String description,
    LocalDate releaseDate,
    String posterUrl,
    String thumbnailUrl
) {}
