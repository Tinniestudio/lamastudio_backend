CREATE TABLE IF NOT EXISTS watch_progress (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID          NOT NULL,
    content_id            UUID          REFERENCES contents(id) ON DELETE SET NULL,
    episode_id            UUID          REFERENCES episodes(id) ON DELETE SET NULL,
    video_asset_id        UUID,
    progress_seconds      INTEGER       NOT NULL DEFAULT 0,
    duration_seconds      INTEGER,
    completion_percentage NUMERIC(5,2) CHECK (completion_percentage IS NULL OR (completion_percentage >= 0 AND completion_percentage <= 100)),
    completed             BOOLEAN       NOT NULL DEFAULT false,
    device_type           VARCHAR(50),
    last_watched_at       TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL;
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS content_id UUID REFERENCES contents(id) ON DELETE SET NULL;
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS episode_id UUID REFERENCES episodes(id) ON DELETE SET NULL;
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS video_asset_id UUID;
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS progress_seconds INTEGER NOT NULL DEFAULT 0;
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS duration_seconds INTEGER;
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS completion_percentage NUMERIC(5,2) CHECK (completion_percentage IS NULL OR (completion_percentage >= 0 AND completion_percentage <= 100));
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS completed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS device_type VARCHAR(50);
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS last_watched_at TIMESTAMPTZ;
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE watch_progress ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_watch_progress_user_content      ON watch_progress(user_id, content_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_watch_progress_user_ep    ON watch_progress(user_id, episode_id)  WHERE episode_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_watch_progress_user_movie ON watch_progress(user_id, content_id)  WHERE episode_id IS NULL;
