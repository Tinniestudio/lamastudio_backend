-- Slugified from title (required, unlike Season.title), scoped to season_id — two episodes named
-- "Pilot" in different seasons/shows can both slug to "pilot" without collision, since the full
-- URL always carries content+season context alongside the episode slug.
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS slug VARCHAR(280);

CREATE OR REPLACE FUNCTION set_episode_slug() RETURNS TRIGGER AS $$
DECLARE
    base_slug TEXT;
    candidate TEXT;
    counter   INTEGER := 2;
BEGIN
    base_slug := slugify(NEW.title);
    candidate := base_slug;
    WHILE EXISTS (
        SELECT 1 FROM episodes
        WHERE season_id = NEW.season_id
          AND slug = candidate
          AND (TG_OP = 'INSERT' OR id != NEW.id)
    ) LOOP
        candidate := base_slug || '-' || counter;
        counter   := counter + 1;
    END LOOP;
    NEW.slug := candidate;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_episode_slug
    BEFORE INSERT OR UPDATE OF title ON episodes
    FOR EACH ROW EXECUTE FUNCTION set_episode_slug();

-- Backfill existing rows using the same base+collision logic as the trigger, processed in
-- episode_number order within each season so ties resolve deterministically (lowest-numbered
-- episode gets the bare slug, later ones get -2/-3/...).
DO $$
DECLARE
    ep        RECORD;
    base_slug TEXT;
    candidate TEXT;
    counter   INTEGER;
BEGIN
    FOR ep IN SELECT id, season_id, title FROM episodes WHERE slug IS NULL ORDER BY season_id, episode_number LOOP
        base_slug := slugify(ep.title);
        candidate := base_slug;
        counter := 2;
        WHILE EXISTS (SELECT 1 FROM episodes WHERE season_id = ep.season_id AND slug = candidate) LOOP
            candidate := base_slug || '-' || counter;
            counter := counter + 1;
        END LOOP;
        UPDATE episodes SET slug = candidate WHERE id = ep.id;
    END LOOP;
END $$;

ALTER TABLE episodes ALTER COLUMN slug SET NOT NULL;
ALTER TABLE episodes DROP CONSTRAINT IF EXISTS uq_episodes_season_slug;
ALTER TABLE episodes ADD CONSTRAINT uq_episodes_season_slug UNIQUE (season_id, slug);
