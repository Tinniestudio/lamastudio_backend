package com.tinniestudio.api.modules.analytics.dto;

import com.tinniestudio.api.modules.analytics.entity.ContentAnalyticsWeekly;

import java.time.LocalDate;
import java.util.UUID;

public record ContentAnalyticsWeeklyDto(
        UUID contentId,
        LocalDate weekStartDate,
        Long views,
        Long uniqueViewers,
        Long completions,
        Long watchTimeSeconds
) {
    public static ContentAnalyticsWeeklyDto from(ContentAnalyticsWeekly e) {
        return new ContentAnalyticsWeeklyDto(
                e.getContentId(),
                e.getWeekStartDate(),
                e.getViews(),
                e.getUniqueViewers(),
                e.getCompletions(),
                e.getWatchTimeSeconds()
        );
    }
}
