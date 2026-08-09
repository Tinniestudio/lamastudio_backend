package com.tinniestudio.api.modules.analytics.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Weekly (ISO week, Monday-start) analytics rollup for a piece of content.
 * Maps to content_analytics_weekly (V44 migration). Populated by a nightly
 * scheduled job that aggregates content_analytics_daily for the current
 * calendar week (Batch 16 #6) — see WeeklyAnalyticsRollupJob.
 */
@Entity
@Table(name = "content_analytics_weekly")
@IdClass(ContentAnalyticsWeeklyId.class)
@Getter @Setter @NoArgsConstructor
public class ContentAnalyticsWeekly {

    @Id
    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    /** Always a Monday — the first day of the ISO-8601 week this row summarizes. */
    @Id
    @Column(name = "week_start_date", nullable = false)
    private LocalDate weekStartDate;

    @Column(name = "views", nullable = false)
    private Long views = 0L;

    @Column(name = "unique_viewers", nullable = false)
    private Long uniqueViewers = 0L;

    @Column(name = "completions", nullable = false)
    private Long completions = 0L;

    @Column(name = "watch_time_seconds", nullable = false)
    private Long watchTimeSeconds = 0L;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
}
