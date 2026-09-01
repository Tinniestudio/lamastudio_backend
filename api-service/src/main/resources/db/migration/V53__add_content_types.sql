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

CREATE INDEX idx_content_types_is_active ON content_types(is_active);
CREATE INDEX idx_content_types_order     ON content_types(display_order);

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

CREATE TRIGGER trg_content_type_slug
    BEFORE INSERT OR UPDATE OF name ON content_types
    FOR EACH ROW EXECUTE FUNCTION set_content_type_slug();

-- Seed the two types today's fixed structural set actually needs. The trigger above fires on
-- these inserts too, computing slug from name — no need to specify it manually.
INSERT INTO content_types (name, structural_kind, display_order) VALUES
    ('Movie', 'SINGLE_VIDEO', 0),
    ('Series', 'MULTI_EPISODE', 1);

-- Add nullable first — a NOT NULL column can't be added to a populated table without a default
-- or a two-step add-then-backfill-then-constrain, and there's no sensible single default here
-- since it must vary per row based on the existing `type` value.
ALTER TABLE contents ADD COLUMN content_type_id UUID;

UPDATE contents c
SET content_type_id = ct.id
FROM content_types ct
WHERE (c.type = 'MOVIE'  AND ct.slug = 'movie')
   OR (c.type = 'SERIES' AND ct.slug = 'series');

ALTER TABLE contents ALTER COLUMN content_type_id SET NOT NULL;
ALTER TABLE contents ADD CONSTRAINT fk_contents_content_type
    FOREIGN KEY (content_type_id) REFERENCES content_types(id);

DROP INDEX IF EXISTS idx_content_type;
CREATE INDEX idx_content_content_type_id ON contents(content_type_id);

ALTER TABLE contents DROP COLUMN type;
