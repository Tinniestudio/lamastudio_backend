-- V40__add_content_analytics_daily.sql

CREATE TABLE content_analytics_daily (
    content_id          UUID    NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    analytics_date      DATE    NOT NULL,
    views               INTEGER NOT NULL DEFAULT 0,
    unique_viewers      INTEGER NOT NULL DEFAULT 0,
    completions         INTEGER NOT NULL DEFAULT 0,
    watch_time_seconds  BIGINT  NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (content_id, analytics_date)
);

CREATE INDEX idx_analytics_content ON content_analytics_daily(content_id);
CREATE INDEX idx_analytics_date    ON content_analytics_daily(analytics_date DESC);
