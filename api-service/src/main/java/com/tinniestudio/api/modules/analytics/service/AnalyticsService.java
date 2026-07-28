package com.tinniestudio.api.modules.analytics.service;

import com.tinniestudio.api.modules.analytics.dto.AnalyticsSummaryResponse;

import java.time.LocalDate;
import java.util.UUID;

public interface AnalyticsService {

    AnalyticsSummaryResponse getContentAnalytics(UUID contentId, LocalDate from, LocalDate to, UUID requesterId, boolean isAdmin);

    AnalyticsSummaryResponse getPartnerAnalytics(UUID partnerId, LocalDate from, LocalDate to);

    String exportContentAnalyticsCsv(UUID contentId, LocalDate from, LocalDate to, UUID requesterId, boolean isAdmin);

    String exportPartnerAnalyticsCsv(UUID partnerId, LocalDate from, LocalDate to);
}
