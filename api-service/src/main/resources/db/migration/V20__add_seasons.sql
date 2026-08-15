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

