package com.tinniestudio.api.modules.analytics.dto;

import com.tinniestudio.api.modules.analytics.entity.ContentAnalyticsDaily;

import java.time.LocalDate;
import java.util.UUID;

public record ContentAnalyticsDailyDto(
        UUID contentId,
        LocalDate analyticsDate,
        Long views,
        Long uniqueViewers,
        Long completions,
        Long watchTimeSeconds
) {
    public static ContentAnalyticsDailyDto from(ContentAnalyticsDaily e) {
        return new ContentAnalyticsDailyDto(
                e.getContentId(),
                e.getAnalyticsDate(),
                e.getViews() != null ? e.getViews().longValue() : null,
                e.getUniqueViewers() != null ? e.getUniqueViewers().longValue() : null,
                e.getCompletions() != null ? e.getCompletions().longValue() : null,
                e.getWatchTimeSeconds()
        );
    }
}
