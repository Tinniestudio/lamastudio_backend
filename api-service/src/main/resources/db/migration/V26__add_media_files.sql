CREATE TABLE IF NOT EXISTS media_files (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    upload_session_id UUID REFERENCES upload_sessions(id),
    user_id           UUID NOT NULL REFERENCES users(id),
    file_type         VARCHAR(50),
    storage_key       VARCHAR(500) NOT NULL,
    original_filename VARCHAR(255),
    mime_type         VARCHAR(100),
    file_size_bytes   BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE media_files ADD COLUMN IF NOT EXISTS upload_session_id UUID REFERENCES upload_sessions(id);
ALTER TABLE media_files ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id);
ALTER TABLE media_files ADD COLUMN IF NOT EXISTS file_type VARCHAR(50);
ALTER TABLE media_files ADD COLUMN IF NOT EXISTS storage_key VARCHAR(500) NOT NULL;
ALTER TABLE media_files ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255);
ALTER TABLE media_files ADD COLUMN IF NOT EXISTS mime_type VARCHAR(100);
ALTER TABLE media_files ADD COLUMN IF NOT EXISTS file_size_bytes BIGINT;
ALTER TABLE media_files ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE media_files ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE media_files ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_media_files_upload_session_id ON media_files(upload_session_id);
CREATE INDEX IF NOT EXISTS idx_media_files_user_id            ON media_files(user_id);
