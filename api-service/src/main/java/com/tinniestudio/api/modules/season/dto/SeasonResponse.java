package com.tinniestudio.api.modules.season.dto;

import com.tinniestudio.api.shared.entity.Season;
import java.time.LocalDate;
import java.util.UUID;

public record SeasonResponse(
    UUID id,
    UUID contentId,
    Integer seasonNumber,
    String title,
    String description,
    LocalDate releaseDate,
    String posterUrl,
    String thumbnailUrl,
    int episodeCount
) {
    public static SeasonResponse from(Season s) {
        return new SeasonResponse(
            s.getId(),
            s.getContent().getId(),
            s.getSeasonNumber(),
            s.getTitle(),
            s.getDescription(),
            s.getReleaseDate(),
            s.getPosterUrl(),
            s.getThumbnailUrl(),
            s.getEpisodes().size()
        );
    }
}
