CREATE TABLE IF NOT EXISTS seasons (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    content_id    UUID        NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    season_number INTEGER     NOT NULL,
    title         VARCHAR(255),
    description   TEXT,
    release_date  DATE,
    poster_url    TEXT,
    thumbnail_url TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (content_id, season_number)
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE seasons ADD COLUMN IF NOT EXISTS content_id UUID NOT NULL REFERENCES contents(id) ON DELETE CASCADE;
ALTER TABLE seasons ADD COLUMN IF NOT EXISTS season_number INTEGER NOT NULL;
ALTER TABLE seasons ADD COLUMN IF NOT EXISTS title VARCHAR(255);
ALTER TABLE seasons ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE seasons ADD COLUMN IF NOT EXISTS release_date DATE;
ALTER TABLE seasons ADD COLUMN IF NOT EXISTS poster_url TEXT;
ALTER TABLE seasons ADD COLUMN IF NOT EXISTS thumbnail_url TEXT;
ALTER TABLE seasons ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE seasons ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

