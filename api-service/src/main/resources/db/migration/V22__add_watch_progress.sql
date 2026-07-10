CREATE TABLE IF NOT EXISTS watch_progress (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID          NOT NULL,
    content_id            UUID          REFERENCES contents(id) ON DELETE SET NULL,
    episode_id            UUID          REFERENCES episodes(id) ON DELETE SET NULL,
    video_asset_id        UUID,
    progress_seconds      INTEGER       NOT NULL DEFAULT 0,
    duration_seconds      INTEGER,
    completion_percentage NUMERIC(5,2),
    completed             BOOLEAN       NOT NULL DEFAULT false,
    device_type           VARCHAR(50),
    last_watched_at       TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_watch_progress_user              ON watch_progress(user_id);
CREATE INDEX idx_watch_progress_user_content      ON watch_progress(user_id, content_id);
CREATE UNIQUE INDEX idx_watch_progress_user_ep    ON watch_progress(user_id, episode_id)  WHERE episode_id IS NOT NULL;
CREATE UNIQUE INDEX idx_watch_progress_user_movie ON watch_progress(user_id, content_id)  WHERE episode_id IS NULL;
