package com.tinniestudio.api.modules.episode.dto;

import com.tinniestudio.api.shared.entity.Episode;
import java.time.LocalDate;
import java.util.UUID;

public record EpisodeResponse(
    UUID id,
    UUID seasonId,
    Integer episodeNumber,
    String title,
    String description,
    LocalDate releaseDate,
    Integer durationSeconds,
    String thumbnailUrl
) {
    public static EpisodeResponse from(Episode e) {
        return new EpisodeResponse(
            e.getId(), e.getSeason().getId(), e.getEpisodeNumber(),
            e.getTitle(), e.getDescription(), e.getReleaseDate(),
            e.getDurationSeconds(), e.getThumbnailUrl()
        );
    }
}
