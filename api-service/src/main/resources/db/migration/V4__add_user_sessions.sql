-- V4__add_user_sessions.sql
-- User session tracking for device/refresh token governance

CREATE TABLE IF NOT EXISTS user_sessions (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash   VARCHAR(255) NOT NULL,
    device_fingerprint   VARCHAR(64),
    device_name          VARCHAR(255),
    ip_address           VARCHAR(45),
    last_used_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ  NOT NULL,
    revoked              BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at           TIMESTAMPTZ,
    revoked_by_admin_id  UUID         REFERENCES admins(id),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS refresh_token_hash VARCHAR(255) NOT NULL;
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS device_fingerprint VARCHAR(64);
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS device_name VARCHAR(255);
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45);
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NOT NULL;
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS revoked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS revoked_by_admin_id UUID REFERENCES admins(id);
ALTER TABLE user_sessions ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_user_sessions_active  ON user_sessions(user_id) WHERE revoked = FALSE;
