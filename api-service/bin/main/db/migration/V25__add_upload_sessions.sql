CREATE TABLE upload_sessions (
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

CREATE INDEX idx_upload_sessions_user_id ON upload_sessions(user_id);
CREATE INDEX idx_upload_sessions_status  ON upload_sessions(upload_status);
