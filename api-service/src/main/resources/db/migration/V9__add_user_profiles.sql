-- V9__add_user_profiles.sql
-- User preference and profile extension table (separate from auth-owned users table)

CREATE TABLE IF NOT EXISTS user_profiles (
    user_id              UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    bio                  TEXT,
    language_code        VARCHAR(10)  DEFAULT 'en',
    country_code         VARCHAR(10),
    timezone             VARCHAR(100),
    notification_email   BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS bio TEXT;
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS language_code VARCHAR(10) DEFAULT 'en';
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS country_code VARCHAR(10);
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS timezone VARCHAR(100);
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS notification_email BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_user_profiles_user_id ON user_profiles(user_id);

CREATE OR REPLACE TRIGGER trg_user_profiles_updated_at
    BEFORE UPDATE ON user_profiles
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
