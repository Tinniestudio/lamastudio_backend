CREATE TABLE IF NOT EXISTS video_assets (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content_id        UUID REFERENCES contents(id) ON DELETE CASCADE,
    season_id         UUID REFERENCES seasons(id) ON DELETE CASCADE,
    episode_id        UUID REFERENCES episodes(id) ON DELETE CASCADE,
    upload_session_id UUID REFERENCES upload_sessions(id),
    asset_type        VARCHAR(50) NOT NULL,
    original_filename VARCHAR(255),
    source_format     VARCHAR(50),
    raw_storage_key   VARCHAR(500) NOT NULL,
    manifest_url      VARCHAR(500),
    duration_seconds  INTEGER,
    width             INTEGER,
    height            INTEGER,
    bitrate           BIGINT,
    codec             VARCHAR(100),
    file_size_bytes   BIGINT,
    processing_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    processing_error  TEXT,
    uploaded_by       UUID NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS content_id UUID REFERENCES contents(id) ON DELETE CASCADE;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS season_id UUID REFERENCES seasons(id) ON DELETE CASCADE;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS episode_id UUID REFERENCES episodes(id) ON DELETE CASCADE;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS upload_session_id UUID REFERENCES upload_sessions(id);
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS asset_type VARCHAR(50) NOT NULL;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255);
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS source_format VARCHAR(50);
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS raw_storage_key VARCHAR(500) NOT NULL;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS manifest_url VARCHAR(500);
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS duration_seconds INTEGER;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS width INTEGER;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS height INTEGER;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS bitrate BIGINT;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS codec VARCHAR(100);
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS file_size_bytes BIGINT;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS processing_status VARCHAR(50) NOT NULL DEFAULT 'PENDING';
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS processing_error TEXT;
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS uploaded_by UUID NOT NULL REFERENCES users(id);
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE video_assets ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE video_assets ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE TABLE IF NOT EXISTS video_variants (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_asset_id UUID NOT NULL REFERENCES video_assets(id) ON DELETE CASCADE,
    resolution     VARCHAR(20) NOT NULL,
    width          INTEGER,
    height         INTEGER,
    bitrate        BIGINT,
    manifest_key   VARCHAR(500),
    segment_count  INTEGER,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE video_variants ADD COLUMN IF NOT EXISTS video_asset_id UUID NOT NULL REFERENCES video_assets(id) ON DELETE CASCADE;
ALTER TABLE video_variants ADD COLUMN IF NOT EXISTS resolution VARCHAR(20) NOT NULL;
ALTER TABLE video_variants ADD COLUMN IF NOT EXISTS width INTEGER;
ALTER TABLE video_variants ADD COLUMN IF NOT EXISTS height INTEGER;
ALTER TABLE video_variants ADD COLUMN IF NOT EXISTS bitrate BIGINT;
ALTER TABLE video_variants ADD COLUMN IF NOT EXISTS manifest_key VARCHAR(500);
ALTER TABLE video_variants ADD COLUMN IF NOT EXISTS segment_count INTEGER;
ALTER TABLE video_variants ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE video_variants ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE video_variants ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_video_assets_content_id  ON video_assets(content_id);
CREATE INDEX IF NOT EXISTS idx_video_assets_episode_id  ON video_assets(episode_id);
CREATE INDEX IF NOT EXISTS idx_video_assets_status      ON video_assets(processing_status);
CREATE INDEX IF NOT EXISTS idx_video_variants_asset_id  ON video_variants(video_asset_id);
