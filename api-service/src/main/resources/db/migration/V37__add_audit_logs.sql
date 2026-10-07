CREATE TABLE IF NOT EXISTS audit_logs (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id    UUID,
    actor_type  VARCHAR(20)  NOT NULL DEFAULT 'ADMIN',
    action      VARCHAR(100) NOT NULL,
    target_type VARCHAR(50),
    target_id   UUID,
    reason      TEXT,
    metadata    TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS actor_id UUID;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS actor_type VARCHAR(20) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS action VARCHAR(100) NOT NULL;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS target_type VARCHAR(50);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS target_id UUID;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS reason TEXT;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS metadata TEXT;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_audit_logs_actor   ON audit_logs(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_target  ON audit_logs(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created ON audit_logs(created_at DESC);
