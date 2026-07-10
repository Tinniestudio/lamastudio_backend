CREATE TABLE IF NOT EXISTS contents (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title            VARCHAR(255) NOT NULL,
    slug             VARCHAR(280) NOT NULL UNIQUE,
    description      TEXT,
    short_description VARCHAR(500),
    type             VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    maturity_rating  VARCHAR(10)  NOT NULL DEFAULT 'NOT_RATED',
    release_date     DATE,
    language         VARCHAR(50),
    country          VARCHAR(50),
    featured         BOOLEAN      NOT NULL DEFAULT false,
    coming_soon      BOOLEAN      NOT NULL DEFAULT false,
    view_count       BIGINT       NOT NULL DEFAULT 0,
    duration_seconds INTEGER,
    poster_url       TEXT,
    thumbnail_url    TEXT,
    created_by       UUID         NOT NULL,
    published_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_content_slug        ON contents(slug);
CREATE INDEX idx_content_type        ON contents(type);
CREATE INDEX idx_content_status      ON contents(status);
CREATE INDEX idx_content_view_count  ON contents(view_count DESC);
CREATE INDEX idx_content_featured    ON contents(featured) WHERE featured = true;
CREATE INDEX idx_content_coming_soon ON contents(coming_soon) WHERE coming_soon = true;
CREATE INDEX idx_content_published   ON contents(published_at DESC) WHERE status = 'PUBLISHED';

CREATE OR REPLACE FUNCTION set_content_slug() RETURNS TRIGGER AS $$
DECLARE
    base_slug TEXT;
    candidate TEXT;
    counter   INTEGER := 2;
BEGIN
    base_slug := slugify(NEW.title);
    candidate := base_slug;
    WHILE EXISTS (
        SELECT 1 FROM contents
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

CREATE TRIGGER trg_content_slug
    BEFORE INSERT OR UPDATE OF title ON contents
    FOR EACH ROW EXECUTE FUNCTION set_content_slug();
