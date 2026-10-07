CREATE TABLE IF NOT EXISTS content_categories (
    content_id  UUID NOT NULL REFERENCES contents(id)   ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (content_id, category_id)
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE content_categories ADD COLUMN IF NOT EXISTS content_id UUID NOT NULL REFERENCES contents(id) ON DELETE CASCADE;
ALTER TABLE content_categories ADD COLUMN IF NOT EXISTS category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_content_categories_category ON content_categories(category_id);

CREATE TABLE IF NOT EXISTS content_cast (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    content_id        UUID         NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    name              VARCHAR(100) NOT NULL,
    role              VARCHAR(100),
    character_name    VARCHAR(100),
    profile_image_url TEXT,
    display_order     INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE content_cast ADD COLUMN IF NOT EXISTS content_id UUID NOT NULL REFERENCES contents(id) ON DELETE CASCADE;
ALTER TABLE content_cast ADD COLUMN IF NOT EXISTS name VARCHAR(100) NOT NULL;
ALTER TABLE content_cast ADD COLUMN IF NOT EXISTS role VARCHAR(100);
ALTER TABLE content_cast ADD COLUMN IF NOT EXISTS character_name VARCHAR(100);
ALTER TABLE content_cast ADD COLUMN IF NOT EXISTS profile_image_url TEXT;
ALTER TABLE content_cast ADD COLUMN IF NOT EXISTS display_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE content_cast ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE content_cast ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE content_cast ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_content_cast_content ON content_cast(content_id);
