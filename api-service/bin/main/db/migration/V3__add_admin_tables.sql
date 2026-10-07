-- V3__add_admin_tables.sql
-- Admin entity, roles, and sessions tables

-- ── Admins ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS admins (
    id                                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email                                 VARCHAR(255) NOT NULL UNIQUE,
    password_hash                         VARCHAR(255) NOT NULL,
    first_name                            VARCHAR(100),
    last_name                             VARCHAR(100),
    account_status                        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    password_reset_token                  VARCHAR(255),
    password_reset_token_expiry           TIMESTAMPTZ,
    password_reset_token_invalidated      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at                            TIMESTAMPTZ
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE admins ADD COLUMN IF NOT EXISTS email VARCHAR(255) NOT NULL UNIQUE;
ALTER TABLE admins ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255) NOT NULL;
ALTER TABLE admins ADD COLUMN IF NOT EXISTS first_name VARCHAR(100);
ALTER TABLE admins ADD COLUMN IF NOT EXISTS last_name VARCHAR(100);
ALTER TABLE admins ADD COLUMN IF NOT EXISTS account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE admins ADD COLUMN IF NOT EXISTS password_reset_token VARCHAR(255);
ALTER TABLE admins ADD COLUMN IF NOT EXISTS password_reset_token_expiry TIMESTAMPTZ;
ALTER TABLE admins ADD COLUMN IF NOT EXISTS password_reset_token_invalidated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE admins ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE admins ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE admins ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE admins ALTER COLUMN id SET DEFAULT gen_random_uuid();

-- ── Admin Roles (element collection) ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS admin_roles (
    admin_id UUID        NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    role     VARCHAR(50) NOT NULL,
    PRIMARY KEY (admin_id, role)
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE admin_roles ADD COLUMN IF NOT EXISTS admin_id UUID NOT NULL REFERENCES admins(id) ON DELETE CASCADE;
ALTER TABLE admin_roles ADD COLUMN IF NOT EXISTS role VARCHAR(50) NOT NULL;

-- ── Admin Sessions ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS admin_sessions (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id            UUID        NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    refresh_token_hash  VARCHAR(255) NOT NULL,
    ip_address          VARCHAR(45),
    last_used_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked             BOOLEAN     NOT NULL DEFAULT FALSE,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS admin_id UUID NOT NULL REFERENCES admins(id) ON DELETE CASCADE;
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS refresh_token_hash VARCHAR(255) NOT NULL;
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45);
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NOT NULL;
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS revoked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE admin_sessions ALTER COLUMN id SET DEFAULT gen_random_uuid();

-- ── Indexes ───────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_admins_email         ON admins(email);
CREATE INDEX IF NOT EXISTS idx_admin_sessions_admin ON admin_sessions(admin_id);
CREATE INDEX IF NOT EXISTS idx_admin_sessions_active ON admin_sessions(admin_id) WHERE revoked = FALSE;

-- ── Auto-update updated_at ────────────────────────────────────────────────────
CREATE OR REPLACE TRIGGER trg_admins_updated_at
    BEFORE UPDATE ON admins
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
