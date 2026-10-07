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

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE watch_history ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE watch_history ADD COLUMN IF NOT EXISTS content_id UUID NOT NULL REFERENCES contents(id);
ALTER TABLE watch_history ADD COLUMN IF NOT EXISTS episode_id UUID REFERENCES episodes(id);
ALTER TABLE watch_history ADD COLUMN IF NOT EXISTS watched_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE watch_history ADD COLUMN IF NOT EXISTS progress_seconds INTEGER;
ALTER TABLE watch_history ADD COLUMN IF NOT EXISTS duration_seconds INTEGER;
ALTER TABLE watch_history ADD COLUMN IF NOT EXISTS device_type VARCHAR(50);
ALTER TABLE watch_history ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_watch_history_user_id ON watch_history(user_id);
CREATE INDEX IF NOT EXISTS idx_watch_history_user_watched ON watch_history(user_id, watched_at DESC);
