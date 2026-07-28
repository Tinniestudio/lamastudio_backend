package com.tinniestudio.api.modules.analytics.service;

import com.tinniestudio.api.modules.analytics.dto.AnalyticsSummaryResponse;
import com.tinniestudio.api.modules.analytics.dto.ContentAnalyticsDailyDto;
import com.tinniestudio.api.modules.analytics.entity.ContentAnalyticsDaily;
import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private final ContentAnalyticsDailyRepository dailyRepo;
    private final ContentRepository contentRepo;

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
        List<Content> contents = contentRepo.findByCreatedBy(partnerId);
        if (contents.isEmpty()) {
            return toSummary(List.of());
        }
        List<ContentAnalyticsDaily> rows = contents.stream()
                .map(Content::getId)
                .flatMap(id -> dailyRepo
                        .findByContentIdAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(id, from, to)
                        .stream())
                .toList();
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
        List<Content> contents = contentRepo.findByCreatedBy(partnerId);
        List<ContentAnalyticsDaily> rows = contents.stream()
                .map(Content::getId)
                .flatMap(id -> dailyRepo
                        .findByContentIdAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(id, from, to)
                        .stream())
                .toList();
        return toCsv(rows);
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
