package com.tinniestudio.api.modules.analytics.service;

import com.tinniestudio.api.modules.analytics.dto.AdminRevenueAnalyticsResponse;
import com.tinniestudio.api.modules.analytics.dto.AnalyticsSummaryResponse;
import com.tinniestudio.api.modules.analytics.dto.ContentAnalyticsDailyDto;
import com.tinniestudio.api.modules.analytics.dto.ContentAnalyticsWeeklyDto;
import com.tinniestudio.api.modules.analytics.dto.WeeklyAnalyticsSummaryResponse;
import com.tinniestudio.api.modules.analytics.entity.ContentAnalyticsDaily;
import com.tinniestudio.api.modules.analytics.entity.ContentAnalyticsWeekly;
import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsWeeklyRepository;
import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private final ContentAnalyticsDailyRepository dailyRepo;
    private final ContentAnalyticsWeeklyRepository weeklyRepo;
    private final ContentRepository contentRepo;
    private final PaymentRepository paymentRepo;

    @Override
    @Transactional(readOnly = true)
    public AnalyticsSummaryResponse getContentAnalytics(UUID contentId, LocalDate from, LocalDate to,
                                                         UUID requesterId, boolean isAdmin) {
        Content content = contentRepo.findById(contentId)
                .orElseThrow(() -> new ResourceNotFoundException("Content not found: " + contentId));
        if (!isAdmin && !content.getCreatedBy().equals(requesterId)) {
            // Return 404 to avoid enumeration attacks
            throw new ResourceNotFoundException("Content not found: " + contentId);
        }
        List<ContentAnalyticsDaily> rows =
                dailyRepo.findByContentIdAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(contentId, from, to);
        return toSummary(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public AnalyticsSummaryResponse getPartnerAnalytics(UUID partnerId, LocalDate from, LocalDate to) {
        List<UUID> contentIds = contentRepo.findByCreatedBy(partnerId).stream().map(Content::getId).toList();
        if (contentIds.isEmpty()) {
            return toSummary(List.of());
        }
        List<ContentAnalyticsDaily> rows =
                dailyRepo.findByContentIdInAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(contentIds, from, to);
        return toSummary(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public String exportContentAnalyticsCsv(UUID contentId, LocalDate from, LocalDate to,
                                             UUID requesterId, boolean isAdmin) {
        Content content = contentRepo.findById(contentId)
                .orElseThrow(() -> new ResourceNotFoundException("Content not found: " + contentId));
        if (!isAdmin && !content.getCreatedBy().equals(requesterId)) {
            throw new ResourceNotFoundException("Content not found: " + contentId);
        }
        List<ContentAnalyticsDaily> rows =
                dailyRepo.findByContentIdAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(contentId, from, to);
        return toCsv(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public String exportPartnerAnalyticsCsv(UUID partnerId, LocalDate from, LocalDate to) {
        List<UUID> contentIds = contentRepo.findByCreatedBy(partnerId).stream().map(Content::getId).toList();
        if (contentIds.isEmpty()) {
            return toCsv(List.of());
        }
        List<ContentAnalyticsDaily> rows =
                dailyRepo.findByContentIdInAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(contentIds, from, to);
        return toCsv(rows);
    }

    // -----------------------------------------------------------------------
    // Gap 1 — admin-only revenue (Batch 16 #4, Batch 13 #5/#6)
    // -----------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public AdminRevenueAnalyticsResponse getAdminRevenueAnalytics(LocalDate from, LocalDate to) {
        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        // 'to' is exclusive-upper-bound at the start of the day AFTER `to`, so the
        // range is inclusive of the entire `to` calendar day.
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        BigDecimal totalRevenue = paymentRepo.sumAmountBetween(fromInstant, toInstant);
        if (totalRevenue == null) totalRevenue = BigDecimal.ZERO;
        long count = paymentRepo.countSuccessfulBetween(fromInstant, toInstant);

        return new AdminRevenueAnalyticsResponse(from, to, totalRevenue, count);
    }

    // -----------------------------------------------------------------------
    // Gap 2 — weekly (Mon-Sun) rollup (Batch 16 #6)
    // -----------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public WeeklyAnalyticsSummaryResponse getContentAnalyticsWeekly(UUID contentId, LocalDate from, LocalDate to,
                                                                     UUID requesterId, boolean isAdmin) {
        Content content = contentRepo.findById(contentId)
                .orElseThrow(() -> new ResourceNotFoundException("Content not found: " + contentId));
        if (!isAdmin && !content.getCreatedBy().equals(requesterId)) {
            throw new ResourceNotFoundException("Content not found: " + contentId);
        }
        List<ContentAnalyticsWeekly> rows =
                weeklyRepo.findByContentIdAndWeekStartDateBetweenOrderByWeekStartDateAsc(contentId, from, to);
        return toWeeklySummary(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public WeeklyAnalyticsSummaryResponse getPartnerAnalyticsWeekly(UUID partnerId, LocalDate from, LocalDate to) {
        List<UUID> contentIds = contentRepo.findByCreatedBy(partnerId).stream().map(Content::getId).toList();
        if (contentIds.isEmpty()) {
            return toWeeklySummary(List.of());
        }
        List<ContentAnalyticsWeekly> rows =
                weeklyRepo.findByContentIdInAndWeekStartDateBetweenOrderByWeekStartDateAsc(contentIds, from, to);
        return toWeeklySummary(rows);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private AnalyticsSummaryResponse toSummary(List<ContentAnalyticsDaily> rows) {
        long totalViews = rows.stream()
                .mapToLong(r -> r.getViews() != null ? r.getViews() : 0L)
                .sum();
        long totalCompletions = rows.stream()
                .mapToLong(r -> r.getCompletions() != null ? r.getCompletions() : 0L)
                .sum();
        long totalUniqueViewers = rows.stream()
                .mapToLong(r -> r.getUniqueViewers() != null ? r.getUniqueViewers() : 0L)
                .sum();

        BigDecimal avgWatch;
        if (rows.isEmpty()) {
            avgWatch = BigDecimal.ZERO;
        } else {
            long totalWatch = rows.stream()
                    .mapToLong(r -> r.getWatchTimeSeconds() != null ? r.getWatchTimeSeconds() : 0L)
                    .sum();
            avgWatch = BigDecimal.valueOf(totalWatch)
                    .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
        }

        List<ContentAnalyticsDailyDto> daily = rows.stream()
                .map(ContentAnalyticsDailyDto::from)
                .toList();

        return new AnalyticsSummaryResponse(totalViews, totalCompletions, totalUniqueViewers, avgWatch, daily);
    }

    private WeeklyAnalyticsSummaryResponse toWeeklySummary(List<ContentAnalyticsWeekly> rows) {
        long totalViews = rows.stream()
                .mapToLong(r -> r.getViews() != null ? r.getViews() : 0L)
                .sum();
        long totalCompletions = rows.stream()
                .mapToLong(r -> r.getCompletions() != null ? r.getCompletions() : 0L)
                .sum();
        long totalUniqueViewers = rows.stream()
                .mapToLong(r -> r.getUniqueViewers() != null ? r.getUniqueViewers() : 0L)
                .sum();

        BigDecimal avgWatch;
        if (rows.isEmpty()) {
            avgWatch = BigDecimal.ZERO;
        } else {
            long totalWatch = rows.stream()
                    .mapToLong(r -> r.getWatchTimeSeconds() != null ? r.getWatchTimeSeconds() : 0L)
                    .sum();
            avgWatch = BigDecimal.valueOf(totalWatch)
                    .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
        }

        List<ContentAnalyticsWeeklyDto> weekly = rows.stream()
                .map(ContentAnalyticsWeeklyDto::from)
                .toList();

        return new WeeklyAnalyticsSummaryResponse(totalViews, totalCompletions, totalUniqueViewers, avgWatch, weekly);
    }

    private String toCsv(List<ContentAnalyticsDaily> rows) {
        StringBuilder sb = new StringBuilder(
                "content_id,analytics_date,views,unique_viewers,completions,watch_time_seconds\n");
        for (ContentAnalyticsDaily r : rows) {
            sb.append(r.getContentId()).append(',')
              .append(r.getAnalyticsDate()).append(',')
              .append(r.getViews() != null ? r.getViews() : 0L).append(',')
              .append(r.getUniqueViewers() != null ? r.getUniqueViewers() : 0L).append(',')
              .append(r.getCompletions() != null ? r.getCompletions() : 0L).append(',')
              .append(r.getWatchTimeSeconds() != null ? r.getWatchTimeSeconds() : 0L).append('\n');
        }
        return sb.toString();
    }
}
