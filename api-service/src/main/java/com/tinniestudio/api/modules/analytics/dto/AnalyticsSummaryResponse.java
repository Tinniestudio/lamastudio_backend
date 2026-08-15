package com.tinniestudio.api.modules.analytics.dto;

import java.math.BigDecimal;
import java.util.List;

public record AnalyticsSummaryResponse(
        long totalViews,
        long totalCompletions,
        long totalUniqueViewers,
        BigDecimal avgWatchTimeSeconds,
        List<ContentAnalyticsDailyDto> daily
) {}
