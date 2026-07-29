package com.tinniestudio.api.modules.analytics.repository;

import com.tinniestudio.api.modules.analytics.entity.ContentAnalyticsDaily;
import com.tinniestudio.api.modules.analytics.entity.ContentAnalyticsDailyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ContentAnalyticsDailyRepository
        extends JpaRepository<ContentAnalyticsDaily, ContentAnalyticsDailyId> {

    List<ContentAnalyticsDaily> findByContentIdAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(
            UUID contentId, LocalDate from, LocalDate to);

    /**
     * Atomically inserts a new row for (content_id, analytics_date) or increments
     * the views counter if a row already exists (PostgreSQL ON CONFLICT upsert).
     */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO content_analytics_daily
                (content_id, analytics_date, views, unique_viewers, completions, watch_time_seconds)
            VALUES (:contentId, :analyticsDate, 1, 0, 0, 0)
            ON CONFLICT (content_id, analytics_date) DO UPDATE
            SET views = content_analytics_daily.views + 1
            """)
    void upsertViewEvent(@Param("contentId") UUID contentId,
                         @Param("analyticsDate") LocalDate analyticsDate);

    /**
     * Atomically inserts a new row or increments the completions counter if the
     * user reached >= 90 % of the content.
     */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO content_analytics_daily
                (content_id, analytics_date, views, unique_viewers, completions, watch_time_seconds)
            VALUES (:contentId, :analyticsDate, 0, 0, 1, 0)
            ON CONFLICT (content_id, analytics_date) DO UPDATE
            SET completions = content_analytics_daily.completions + 1
            """)
    void upsertCompletionEvent(@Param("contentId") UUID contentId,
                               @Param("analyticsDate") LocalDate analyticsDate);
}
