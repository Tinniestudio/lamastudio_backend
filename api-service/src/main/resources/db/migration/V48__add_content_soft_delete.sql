ALTER TABLE contents ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_contents_deleted_at ON contents(deleted_at);
