ALTER TABLE upload_sessions ADD COLUMN multipart_upload_id VARCHAR(255);
ALTER TABLE upload_sessions ADD COLUMN part_size_bytes BIGINT;
