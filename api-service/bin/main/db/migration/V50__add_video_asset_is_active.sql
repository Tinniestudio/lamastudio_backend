ALTER TABLE video_assets ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT false;

-- Backfill: for each MOST-SPECIFIC target (episode if set, else season, else content) and
-- asset_type, mark the most-recently-created READY row active. Partitioning by
-- COALESCE(episode_id, season_id, content_id) rather than content_id alone matters once a
-- series has more than one episode sharing the same parent content — partitioning by content_id
-- alone would collapse activation down to a single episode across the whole show. In practice
-- this backfill only ever sees content-only (movie) rows today, since nothing set
-- episode_id/season_id before this same migration's feature branch — but it's written to match
-- the ongoing write path's scoping exactly (see VideoActivationService.activateAndRetireSiblings),
-- so the two mechanisms can't silently diverge later.
WITH ranked AS (
    SELECT id, COALESCE(episode_id, season_id, content_id) AS target_id, asset_type,
           ROW_NUMBER() OVER (PARTITION BY COALESCE(episode_id, season_id, content_id), asset_type ORDER BY created_at DESC) AS rn
    FROM video_assets
    WHERE processing_status = 'READY'
      AND (content_id IS NOT NULL OR season_id IS NOT NULL OR episode_id IS NOT NULL)
)
UPDATE video_assets
SET is_active = true
WHERE id IN (SELECT id FROM ranked WHERE rn = 1);
