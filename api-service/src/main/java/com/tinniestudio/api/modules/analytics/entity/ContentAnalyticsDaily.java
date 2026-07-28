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
