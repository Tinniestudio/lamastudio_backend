package com.tinniestudio.api.modules.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FavoriteResponse(
    UUID id,
    UUID contentId,
    Instant createdAt,
    ContentSummaryResponse content
) {}
