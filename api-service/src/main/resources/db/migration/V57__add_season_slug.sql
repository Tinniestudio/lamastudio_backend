-- Computed from season_number alone — no collision loop needed, since uniqueness is already
-- guaranteed by the existing (content_id, season_number) unique constraint, and season_number is
-- the only Season field guaranteed present (title is optional and often blank).
ALTER TABLE seasons ADD COLUMN slug VARCHAR(50);

CREATE OR REPLACE FUNCTION set_season_slug() RETURNS TRIGGER AS $$
BEGIN
    NEW.slug := 'season-' || NEW.season_number::text;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_season_slug
    BEFORE INSERT OR UPDATE OF season_number ON seasons
    FOR EACH ROW EXECUTE FUNCTION set_season_slug();

-- Backfill existing rows — the trigger only fires on future INSERT/UPDATE, not retroactively.
UPDATE seasons SET slug = 'season-' || season_number::text WHERE slug IS NULL;

ALTER TABLE seasons ALTER COLUMN slug SET NOT NULL;
ALTER TABLE seasons ADD CONSTRAINT uq_seasons_content_slug UNIQUE (content_id, slug);
