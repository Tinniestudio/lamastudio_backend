CREATE TABLE IF NOT EXISTS episodes (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    season_id        UUID         NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
    episode_number   INTEGER      NOT NULL,
    title            VARCHAR(255) NOT NULL,
    description      TEXT,
    release_date     DATE,
    duration_seconds INTEGER,
    thumbnail_url    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (season_id, episode_number)
);

CREATE INDEX idx_episodes_season_id ON episodes(season_id);
