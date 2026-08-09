package com.tinniestudio.api.modules.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Strictly admin-only revenue breakdown, sourced from the {@code payments} table
 * (Batch 16 #4). Never expose this type — or a field like it — on any
 * partner-facing analytics response; gross revenue/earnings stay admin-only
 * everywhere (Batch 13 #5/#6).
 */
public record AdminRevenueAnalyticsResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal totalRevenue,
        long successfulPaymentCount
) {}
