-- V4__add_user_sessions.sql
-- User session tracking for device/refresh token governance

CREATE TABLE user_sessions (
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

CREATE INDEX idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX idx_user_sessions_active  ON user_sessions(user_id) WHERE revoked = FALSE;
