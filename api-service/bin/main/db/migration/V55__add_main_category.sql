-- Add nullable first, same reasoning as V53's content_type_id: a NOT NULL column can't be added
-- to a populated table without a default, and the correct value varies per row.
ALTER TABLE contents ADD COLUMN IF NOT EXISTS main_category VARCHAR(20);

-- Priority 1: explicitly tagged "Sermons" (the about-to-be-retired genre category below).
UPDATE contents c
SET main_category = 'SERMONS'
WHERE EXISTS (
    SELECT 1 FROM content_categories cc
    JOIN categories cat ON cat.id = cc.category_id
    WHERE cc.content_id = c.id AND cat.slug = 'sermons'
);

-- Priority 2: explicitly tagged "Kids".
UPDATE contents c
SET main_category = 'KIDS'
WHERE c.main_category IS NULL
  AND EXISTS (
    SELECT 1 FROM content_categories cc
    JOIN categories cat ON cat.id = cc.category_id
    WHERE cc.content_id = c.id AND cat.slug = 'kids'
);

-- Priority 3: series (MULTI_EPISODE) with no Kids/Sermons tag.
UPDATE contents c
SET main_category = 'TV_SHOWS'
FROM content_types ct
WHERE c.main_category IS NULL
  AND c.content_type_id = ct.id
  AND ct.structural_kind = 'MULTI_EPISODE';

-- Priority 4 (default fallback): everything else.
UPDATE contents
SET main_category = 'MOVIES'
WHERE main_category IS NULL;

ALTER TABLE contents ALTER COLUMN main_category SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_content_main_category ON contents(main_category);

-- Retire the now-redundant genre rows — mainCategory alone expresses "Kids"/"Sermons" going
-- forward; keeping both would let a title be simultaneously mainCategory=KIDS and
-- subcategory-tagged "Kids", which is confusing and redundant. Deactivated, not deleted, to
-- preserve historical content_categories join rows (used by the backfill above).
UPDATE categories SET is_active = false WHERE slug IN ('kids', 'sermons');
