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

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS season_id UUID NOT NULL REFERENCES seasons(id) ON DELETE CASCADE;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS episode_number INTEGER NOT NULL;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS title VARCHAR(255) NOT NULL;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS release_date DATE;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS duration_seconds INTEGER;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS thumbnail_url TEXT;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

