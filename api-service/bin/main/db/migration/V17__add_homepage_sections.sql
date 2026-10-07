CREATE TABLE IF NOT EXISTS homepage_sections (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    title         VARCHAR(100) NOT NULL,
    section_type  VARCHAR(30)  NOT NULL UNIQUE,
    category_id   UUID         REFERENCES categories(id) ON DELETE SET NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE homepage_sections ADD COLUMN IF NOT EXISTS title VARCHAR(100) NOT NULL;
ALTER TABLE homepage_sections ADD COLUMN IF NOT EXISTS section_type VARCHAR(30) NOT NULL UNIQUE;
ALTER TABLE homepage_sections ADD COLUMN IF NOT EXISTS category_id UUID REFERENCES categories(id) ON DELETE SET NULL;
ALTER TABLE homepage_sections ADD COLUMN IF NOT EXISTS display_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE homepage_sections ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE homepage_sections ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE homepage_sections ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE homepage_sections ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_homepage_sections_order ON homepage_sections(display_order);
