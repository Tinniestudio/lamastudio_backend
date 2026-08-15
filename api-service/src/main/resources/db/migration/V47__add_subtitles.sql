CREATE TABLE subtitles (
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

CREATE INDEX idx_subtitles_video_asset_id ON subtitles(video_asset_id);
