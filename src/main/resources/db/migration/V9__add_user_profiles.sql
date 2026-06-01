-- V9__add_user_profiles.sql
-- User preference and profile extension table (separate from auth-owned users table)

CREATE TABLE user_profiles (
    user_id              UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    bio                  TEXT,
    language_code        VARCHAR(10)  DEFAULT 'en',
    country_code         VARCHAR(10),
    timezone             VARCHAR(100),
    notification_email   BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_profiles_user_id ON user_profiles(user_id);

CREATE TRIGGER trg_user_profiles_updated_at
    BEFORE UPDATE ON user_profiles
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
