package com.tinniestudio.api.modules.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchResponse(
    List<ContentSummaryResponse> results,
    long total,
    int page,
    int limit,
    int totalPages
) {}
