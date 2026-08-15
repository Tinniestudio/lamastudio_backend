CREATE TABLE media_files (
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

CREATE INDEX idx_media_files_upload_session_id ON media_files(upload_session_id);
CREATE INDEX idx_media_files_user_id            ON media_files(user_id);
