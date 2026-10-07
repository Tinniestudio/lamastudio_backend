CREATE TABLE IF NOT EXISTS content_types (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL UNIQUE,
    slug            VARCHAR(120) NOT NULL UNIQUE,
    description     TEXT,
    structural_kind VARCHAR(20) NOT NULL,
    display_order   INTEGER     NOT NULL DEFAULT 0,
    is_active       BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE content_types ADD COLUMN IF NOT EXISTS name VARCHAR(100) NOT NULL UNIQUE;
ALTER TABLE content_types ADD COLUMN IF NOT EXISTS slug VARCHAR(120) NOT NULL UNIQUE;
ALTER TABLE content_types ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE content_types ADD COLUMN IF NOT EXISTS structural_kind VARCHAR(20) NOT NULL;
ALTER TABLE content_types ADD COLUMN IF NOT EXISTS display_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE content_types ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE content_types ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE content_types ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE content_types ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_content_types_is_active ON content_types(is_active);
CREATE INDEX IF NOT EXISTS idx_content_types_order     ON content_types(display_order);

CREATE OR REPLACE FUNCTION set_content_type_slug() RETURNS TRIGGER AS $$
DECLARE
    base_slug TEXT;
    candidate TEXT;
    counter   INTEGER := 2;
BEGIN
    base_slug := slugify(NEW.name);
    candidate := base_slug;
    WHILE EXISTS (
        SELECT 1 FROM content_types
        WHERE slug = candidate
          AND (TG_OP = 'INSERT' OR id != NEW.id)
    ) LOOP
        candidate := base_slug || '-' || counter;
        counter   := counter + 1;
    END LOOP;
    NEW.slug := candidate;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_content_type_slug
    BEFORE INSERT OR UPDATE OF name ON content_types
    FOR EACH ROW EXECUTE FUNCTION set_content_type_slug();

-- Seed the two types today's fixed structural set actually needs. The trigger above fires on
-- these inserts too, computing slug from name — no need to specify it manually.
INSERT INTO content_types (name, structural_kind, display_order) VALUES
    ('Movie', 'SINGLE_VIDEO', 0),
    ('Series', 'MULTI_EPISODE', 1)
ON CONFLICT (name) DO NOTHING;

-- Add nullable first — a NOT NULL column can't be added to a populated table without a default
-- or a two-step add-then-backfill-then-constrain, and there's no sensible single default here
-- since it must vary per row based on the existing `type` value.
ALTER TABLE contents ADD COLUMN IF NOT EXISTS content_type_id UUID;

-- Guarded on `type` still existing: if a prior out-of-band change already dropped it (or
-- already backfilled+dropped it via an earlier partial run of this exact migration), this
-- block is a no-op rather than a hard failure on a column that's no longer there.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'contents' AND column_name = 'type'
    ) THEN
        UPDATE contents c
        SET content_type_id = ct.id
        FROM content_types ct
        WHERE c.content_type_id IS NULL
          AND ((c.type = 'MOVIE'  AND ct.slug = 'movie')
           OR (c.type = 'SERIES' AND ct.slug = 'series'));
    END IF;
END $$;

ALTER TABLE contents ALTER COLUMN content_type_id SET NOT NULL;
ALTER TABLE contents DROP CONSTRAINT IF EXISTS fk_contents_content_type;
ALTER TABLE contents ADD CONSTRAINT fk_contents_content_type
    FOREIGN KEY (content_type_id) REFERENCES content_types(id);

DROP INDEX IF EXISTS idx_content_type;
CREATE INDEX IF NOT EXISTS idx_content_content_type_id ON contents(content_type_id);

ALTER TABLE contents DROP COLUMN IF EXISTS type;
