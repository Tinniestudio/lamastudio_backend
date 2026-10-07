CREATE TABLE IF NOT EXISTS subtitles (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_asset_id UUID NOT NULL REFERENCES video_assets(id) ON DELETE CASCADE,
    language_code  VARCHAR(50) NOT NULL,
    label          VARCHAR(255),
    file_url       VARCHAR(500) NOT NULL,
    format         VARCHAR(50),
    is_default     BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE subtitles ADD COLUMN IF NOT EXISTS video_asset_id UUID NOT NULL REFERENCES video_assets(id) ON DELETE CASCADE;
ALTER TABLE subtitles ADD COLUMN IF NOT EXISTS language_code VARCHAR(50) NOT NULL;
ALTER TABLE subtitles ADD COLUMN IF NOT EXISTS label VARCHAR(255);
ALTER TABLE subtitles ADD COLUMN IF NOT EXISTS file_url VARCHAR(500) NOT NULL;
ALTER TABLE subtitles ADD COLUMN IF NOT EXISTS format VARCHAR(50);
ALTER TABLE subtitles ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE subtitles ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE subtitles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE subtitles ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_subtitles_video_asset_id ON subtitles(video_asset_id);
