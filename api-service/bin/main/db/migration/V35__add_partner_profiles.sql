CREATE TABLE IF NOT EXISTS partner_profiles (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID         NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    company_name             VARCHAR(255),
    website_url              VARCHAR(500),
    bio                      TEXT,
    logo_url                 VARCHAR(500),
    revenue_share_percentage NUMERIC(5,2) NOT NULL DEFAULT 70.00,
    is_verified              BOOLEAN      NOT NULL DEFAULT true,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE partner_profiles ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE partner_profiles ADD COLUMN IF NOT EXISTS company_name VARCHAR(255);
ALTER TABLE partner_profiles ADD COLUMN IF NOT EXISTS website_url VARCHAR(500);
ALTER TABLE partner_profiles ADD COLUMN IF NOT EXISTS bio TEXT;
ALTER TABLE partner_profiles ADD COLUMN IF NOT EXISTS logo_url VARCHAR(500);
ALTER TABLE partner_profiles ADD COLUMN IF NOT EXISTS revenue_share_percentage NUMERIC(5,2) NOT NULL DEFAULT 70.00;
ALTER TABLE partner_profiles ADD COLUMN IF NOT EXISTS is_verified BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE partner_profiles ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE partner_profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE partner_profiles ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_partner_profiles_user ON partner_profiles(user_id);
