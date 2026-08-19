ALTER TABLE video_assets ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT false;

-- Backfill: for each (content_id, asset_type) pair, mark the most-recently-created READY row
-- active. This is what PlaybackServiceImpl's old findTopBy...ProcessingStatus query implicitly
-- relied on (no explicit ORDER BY existed there — "most recent" was Hibernate/Postgres's de
-- facto but implementation-defined behavior); this migration makes that choice explicit and
-- guaranteed going forward, so the playback-query swap in this same plan doesn't regress
-- anything that was already working.
WITH ranked AS (
    SELECT id, content_id, asset_type,
           ROW_NUMBER() OVER (PARTITION BY content_id, asset_type ORDER BY created_at DESC) AS rn
    FROM video_assets
    WHERE processing_status = 'READY' AND content_id IS NOT NULL
)
UPDATE video_assets
SET is_active = true
WHERE id IN (SELECT id FROM ranked WHERE rn = 1);
