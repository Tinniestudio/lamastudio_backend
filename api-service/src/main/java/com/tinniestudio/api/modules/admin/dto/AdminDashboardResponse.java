package com.tinniestudio.api.modules.admin.dto;

import java.math.BigDecimal;
import java.util.Map;

public record AdminDashboardResponse(
    long totalUsers,
    long newUsersThisWeek,
    long activeSubscriptions,
    BigDecimal revenueThisMonth,
    long contentInReview,
    long processingFailures,
    long storageBytesUsed,
    Map<String, Integer> queueDepths
) {}
