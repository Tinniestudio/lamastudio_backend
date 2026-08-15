package com.tinniestudio.api.modules.analytics.service;

import com.tinniestudio.api.modules.analytics.dto.AdminRevenueAnalyticsResponse;
import com.tinniestudio.api.modules.analytics.dto.AnalyticsSummaryResponse;
import com.tinniestudio.api.modules.analytics.dto.WeeklyAnalyticsSummaryResponse;

import java.time.LocalDate;
import java.util.UUID;

public interface AnalyticsService {

    AnalyticsSummaryResponse getContentAnalytics(UUID contentId, LocalDate from, LocalDate to, UUID requesterId, boolean isAdmin);

    AnalyticsSummaryResponse getPartnerAnalytics(UUID partnerId, LocalDate from, LocalDate to);

    String exportContentAnalyticsCsv(UUID contentId, LocalDate from, LocalDate to, UUID requesterId, boolean isAdmin);

    String exportPartnerAnalyticsCsv(UUID partnerId, LocalDate from, LocalDate to);

    /**
     * Strictly admin-only revenue breakdown sourced from the payments table (Batch 16 #4).
     * Must never be reachable from a partner-facing endpoint (Batch 13 #5/#6).
     */
    AdminRevenueAnalyticsResponse getAdminRevenueAnalytics(LocalDate from, LocalDate to);

    /** Weekly (Mon-Sun, ISO week) rollup, read from content_analytics_weekly (Batch 16 #6). */
    WeeklyAnalyticsSummaryResponse getContentAnalyticsWeekly(UUID contentId, LocalDate from, LocalDate to, UUID requesterId, boolean isAdmin);

    WeeklyAnalyticsSummaryResponse getPartnerAnalyticsWeekly(UUID partnerId, LocalDate from, LocalDate to);
}
