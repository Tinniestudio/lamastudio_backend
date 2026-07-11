CREATE TABLE IF NOT EXISTS content_categories (
    content_id  UUID NOT NULL REFERENCES contents(id)   ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (content_id, category_id)
);

CREATE INDEX idx_content_categories_category ON content_categories(category_id);

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

CREATE INDEX idx_content_cast_content ON content_cast(content_id);
