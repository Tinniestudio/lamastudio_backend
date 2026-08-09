-- V44__add_content_analytics_weekly.sql
-- Weekly (ISO week, Monday-start) rollup of content_analytics_daily (Batch 16 #6).

CREATE TABLE content_analytics_weekly (
    content_id          UUID    NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    week_start_date     DATE    NOT NULL,
    views               INTEGER NOT NULL DEFAULT 0,
    unique_viewers      INTEGER NOT NULL DEFAULT 0,
    completions         INTEGER NOT NULL DEFAULT 0,
    watch_time_seconds  BIGINT  NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (content_id, week_start_date)
);

CREATE INDEX idx_analytics_weekly_content ON content_analytics_weekly(content_id);
CREATE INDEX idx_analytics_weekly_week    ON content_analytics_weekly(week_start_date DESC);
