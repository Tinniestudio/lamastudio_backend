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

CREATE INDEX idx_partner_profiles_user ON partner_profiles(user_id);
