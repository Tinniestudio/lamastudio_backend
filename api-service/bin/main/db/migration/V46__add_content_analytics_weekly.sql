-- V44__add_content_analytics_weekly.sql
-- Weekly (ISO week, Monday-start) rollup of content_analytics_daily (Batch 16 #6).

CREATE TABLE IF NOT EXISTS content_analytics_weekly (
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

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE content_analytics_weekly ADD COLUMN IF NOT EXISTS content_id UUID NOT NULL REFERENCES contents(id) ON DELETE CASCADE;
ALTER TABLE content_analytics_weekly ADD COLUMN IF NOT EXISTS week_start_date DATE NOT NULL;
ALTER TABLE content_analytics_weekly ADD COLUMN IF NOT EXISTS views INTEGER NOT NULL DEFAULT 0;
ALTER TABLE content_analytics_weekly ADD COLUMN IF NOT EXISTS unique_viewers INTEGER NOT NULL DEFAULT 0;
ALTER TABLE content_analytics_weekly ADD COLUMN IF NOT EXISTS completions INTEGER NOT NULL DEFAULT 0;
ALTER TABLE content_analytics_weekly ADD COLUMN IF NOT EXISTS watch_time_seconds BIGINT NOT NULL DEFAULT 0;
ALTER TABLE content_analytics_weekly ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE content_analytics_weekly ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_analytics_weekly_content ON content_analytics_weekly(content_id);
CREATE INDEX IF NOT EXISTS idx_analytics_weekly_week    ON content_analytics_weekly(week_start_date DESC);
