package com.tinniestudio.api.modules.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WatchHistoryResponse(
    UUID id,
    UUID contentId,
    UUID episodeId,
    Instant watchedAt,
    Integer progressSeconds,
    Integer durationSeconds,
    String deviceType,
    ContentSummaryResponse content
) {}
