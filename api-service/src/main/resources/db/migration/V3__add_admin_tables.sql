-- V3__add_admin_tables.sql
-- Admin entity, roles, and sessions tables

-- ── Admins ────────────────────────────────────────────────────────────────────
CREATE TABLE admins (
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

-- ── Admin Roles (element collection) ─────────────────────────────────────────
CREATE TABLE admin_roles (
    admin_id UUID        NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    role     VARCHAR(50) NOT NULL,
    PRIMARY KEY (admin_id, role)
);

-- ── Admin Sessions ────────────────────────────────────────────────────────────
CREATE TABLE admin_sessions (
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

-- ── Indexes ───────────────────────────────────────────────────────────────────
CREATE INDEX idx_admins_email         ON admins(email);
CREATE INDEX idx_admin_sessions_admin ON admin_sessions(admin_id);
CREATE INDEX idx_admin_sessions_active ON admin_sessions(admin_id) WHERE revoked = FALSE;

-- ── Auto-update updated_at ────────────────────────────────────────────────────
CREATE TRIGGER trg_admins_updated_at
    BEFORE UPDATE ON admins
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
