CREATE TABLE IF NOT EXISTS homepage_sections (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    title         VARCHAR(100) NOT NULL,
    section_type  VARCHAR(30)  NOT NULL,
    category_id   UUID         REFERENCES categories(id) ON DELETE SET NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_homepage_sections_order ON homepage_sections(display_order);
