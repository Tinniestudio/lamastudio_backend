CREATE TABLE video_assets (
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

CREATE TABLE video_variants (
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

CREATE INDEX idx_video_assets_content_id  ON video_assets(content_id);
CREATE INDEX idx_video_assets_episode_id  ON video_assets(episode_id);
CREATE INDEX idx_video_assets_status      ON video_assets(processing_status);
CREATE INDEX idx_video_variants_asset_id  ON video_variants(video_asset_id);
