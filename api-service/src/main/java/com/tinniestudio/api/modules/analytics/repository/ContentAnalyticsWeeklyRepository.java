package com.tinniestudio.api.modules.analytics.repository;

import com.tinniestudio.api.modules.analytics.entity.ContentAnalyticsWeekly;
import com.tinniestudio.api.modules.analytics.entity.ContentAnalyticsWeeklyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ContentAnalyticsWeeklyRepository
        extends JpaRepository<ContentAnalyticsWeekly, ContentAnalyticsWeeklyId> {

    List<ContentAnalyticsWeekly> findByContentIdAndWeekStartDateBetweenOrderByWeekStartDateAsc(
            UUID contentId, LocalDate from, LocalDate to);

    /**
     * Batch variant of the single-content lookup above, for a partner's whole content catalog —
     * one query instead of one-per-content-id (was an N+1 in getPartnerAnalyticsWeekly).
     */
    List<ContentAnalyticsWeekly> findByContentIdInAndWeekStartDateBetweenOrderByWeekStartDateAsc(
            Collection<UUID> contentIds, LocalDate from, LocalDate to);

    /**
     * Aggregates content_analytics_daily rows for [weekStart, weekEnd] (inclusive, both Monday
     * and Sunday of the same ISO week) grouped by content, and upserts the result into
     * content_analytics_weekly. Re-running for the same week recomputes the totals from scratch
     * (idempotent), which lets the nightly job refresh the current, still-in-progress week each
     * night without double-counting.
     */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO content_analytics_weekly
                (content_id, week_start_date, views, unique_viewers, completions, watch_time_seconds)
            SELECT content_id, :weekStart, SUM(views), SUM(unique_viewers), SUM(completions), SUM(watch_time_seconds)
            FROM content_analytics_daily
            WHERE analytics_date BETWEEN :weekStart AND :weekEnd
            GROUP BY content_id
            ON CONFLICT (content_id, week_start_date) DO UPDATE
            SET views              = EXCLUDED.views,
                unique_viewers      = EXCLUDED.unique_viewers,
                completions         = EXCLUDED.completions,
                watch_time_seconds  = EXCLUDED.watch_time_seconds,
                updated_at          = now()
            """)
    int rollupWeek(@Param("weekStart") LocalDate weekStart, @Param("weekEnd") LocalDate weekEnd);
}
