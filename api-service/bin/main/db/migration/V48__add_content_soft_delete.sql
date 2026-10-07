ALTER TABLE contents ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_contents_deleted_at ON contents(deleted_at);
