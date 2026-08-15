package com.tinniestudio.api.modules.analytics.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Daily analytics rollup for a piece of content.
 * Maps to content_analytics_daily (V40 migration).
 * Columns: views, unique_viewers, completions, watch_time_seconds.
 */
@Entity
@Table(name = "content_analytics_daily")
@IdClass(ContentAnalyticsDailyId.class)
@Getter @Setter @NoArgsConstructor
public class ContentAnalyticsDaily {

    @Id
    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Id
    @Column(name = "analytics_date", nullable = false)
    private LocalDate analyticsDate;

    // Integer, matching the actual DB column type (V40: INTEGER) — these are single-day counts
    // for one content item, never realistically approaching 2^31. watchTimeSeconds below stays
    // Long since seconds accumulate much faster and the column is BIGINT.
    @Column(name = "views", nullable = false)
    private Integer views = 0;

    @Column(name = "unique_viewers", nullable = false)
    private Integer uniqueViewers = 0;

    @Column(name = "completions", nullable = false)
    private Integer completions = 0;

    @Column(name = "watch_time_seconds", nullable = false)
    private Long watchTimeSeconds = 0L;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
}
