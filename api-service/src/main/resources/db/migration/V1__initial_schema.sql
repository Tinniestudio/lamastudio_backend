-- V1__initial_schema.sql
-- LamaStudio Authentication Module — Initial Database Schema

-- ── Extensions ────────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── Roles ─────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS roles (
    id   BIGSERIAL    PRIMARY KEY,
    name VARCHAR(50)  NOT NULL UNIQUE
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE roles ADD COLUMN IF NOT EXISTS name VARCHAR(50) NOT NULL UNIQUE;

-- ── Users ─────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id                                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email                             VARCHAR(255) NOT NULL UNIQUE,
    password_hash                     VARCHAR(255),
    provider                          VARCHAR(20)  NOT NULL DEFAULT 'LOCAL',
    provider_id                       VARCHAR(255),
    first_name                        VARCHAR(100),
    last_name                         VARCHAR(100),
    display_name                      VARCHAR(150),
    date_of_birth                     DATE,
    phone_number                      VARCHAR(30),
    avatar_url                        VARCHAR(2048),
    account_status                    VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    email_verified                    BOOLEAN      NOT NULL DEFAULT FALSE,
    email_verification_token          VARCHAR(255),
    email_verification_token_expiry   TIMESTAMPTZ,
    password_reset_token              VARCHAR(255),
    password_reset_token_expiry       TIMESTAMPTZ,
    created_at                        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at                        TIMESTAMPTZ
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255) NOT NULL UNIQUE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider_id VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS first_name VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_name VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name VARCHAR(150);
ALTER TABLE users ADD COLUMN IF NOT EXISTS date_of_birth DATE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_number VARCHAR(30);
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(2048);
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verification_token VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verification_token_expiry TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_reset_token VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_reset_token_expiry TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE users ALTER COLUMN id SET DEFAULT gen_random_uuid();

-- ── User Roles (join table) ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE user_roles ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE user_roles ADD COLUMN IF NOT EXISTS role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE;

-- ── Indexes ───────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_users_email          ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_provider_id    ON users(provider, provider_id) WHERE provider_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_users_account_status ON users(account_status);
CREATE INDEX IF NOT EXISTS idx_users_deleted_at     ON users(deleted_at) WHERE deleted_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_roles_user_id   ON user_roles(user_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_role_id   ON user_roles(role_id);

-- ── Auto-update updated_at ────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
