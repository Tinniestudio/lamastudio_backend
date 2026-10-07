ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS multipart_upload_id VARCHAR(255);
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS part_size_bytes BIGINT;
