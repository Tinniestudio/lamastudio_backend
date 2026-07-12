-- V30__add_search_vector.sql

-- 1. Add the tsvector column (nullable — trigger fills it on save)
ALTER TABLE contents ADD COLUMN IF NOT EXISTS search_vector tsvector;

-- 2. GIN index for fast full-text lookups
CREATE INDEX IF NOT EXISTS idx_contents_search ON contents USING GIN(search_vector);

-- 3. Function that builds the tsvector
CREATE OR REPLACE FUNCTION update_content_search_vector()
RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', coalesce(NEW.title, '')),             'A') ||
        setweight(to_tsvector('english', coalesce(NEW.short_description, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.description, '')),       'C');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 4. Fire before every insert or update
CREATE OR REPLACE TRIGGER trg_content_search_vector
    BEFORE INSERT OR UPDATE ON contents
    FOR EACH ROW EXECUTE FUNCTION update_content_search_vector();

-- 5. Backfill existing rows
UPDATE contents SET search_vector =
    setweight(to_tsvector('english', coalesce(title, '')),             'A') ||
    setweight(to_tsvector('english', coalesce(short_description, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(description, '')),       'C');
