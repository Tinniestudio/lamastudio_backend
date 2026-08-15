CREATE TABLE IF NOT EXISTS watch_history (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_id       UUID        NOT NULL REFERENCES contents(id),
    episode_id       UUID        REFERENCES episodes(id),
    watched_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    progress_seconds INTEGER,
    duration_seconds INTEGER,
    device_type      VARCHAR(50),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_watch_history_user_id ON watch_history(user_id);
CREATE INDEX idx_watch_history_user_watched ON watch_history(user_id, watched_at DESC);
