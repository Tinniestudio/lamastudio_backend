package com.tinniestudio.api.modules.analytics.dto;

import java.math.BigDecimal;
import java.util.List;

public record WeeklyAnalyticsSummaryResponse(
        long totalViews,
        long totalCompletions,
        long totalUniqueViewers,
        BigDecimal avgWatchTimeSeconds,
        List<ContentAnalyticsWeeklyDto> weekly
) {}
