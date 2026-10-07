CREATE TABLE IF NOT EXISTS upload_sessions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id),
    upload_type             VARCHAR(50) NOT NULL,
    target_entity_type      VARCHAR(50),
    target_entity_id        UUID,
    storage_key             VARCHAR(500) NOT NULL,
    original_filename       VARCHAR(255),
    mime_type               VARCHAR(100),
    expected_max_size_bytes BIGINT,
    upload_status           VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    presigned_url           TEXT,
    expires_at              TIMESTAMPTZ NOT NULL,
    completed_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id);
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS upload_type VARCHAR(50) NOT NULL;
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS target_entity_type VARCHAR(50);
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS target_entity_id UUID;
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS storage_key VARCHAR(500) NOT NULL;
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255);
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS mime_type VARCHAR(100);
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS expected_max_size_bytes BIGINT;
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS upload_status VARCHAR(50) NOT NULL DEFAULT 'PENDING';
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS presigned_url TEXT;
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NOT NULL;
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE upload_sessions ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_upload_sessions_user_id ON upload_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_upload_sessions_status  ON upload_sessions(upload_status);
