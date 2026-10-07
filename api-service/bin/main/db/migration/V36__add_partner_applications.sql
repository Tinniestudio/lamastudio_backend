CREATE TABLE IF NOT EXISTS partner_applications (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    company_name     VARCHAR(255) NOT NULL,
    description      TEXT,
    website_url      VARCHAR(500),
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    rejection_reason TEXT,
    reviewed_by      UUID         REFERENCES users(id),
    reviewed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE partner_applications ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE partner_applications ADD COLUMN IF NOT EXISTS company_name VARCHAR(255) NOT NULL;
ALTER TABLE partner_applications ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE partner_applications ADD COLUMN IF NOT EXISTS website_url VARCHAR(500);
ALTER TABLE partner_applications ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE partner_applications ADD COLUMN IF NOT EXISTS rejection_reason TEXT;
ALTER TABLE partner_applications ADD COLUMN IF NOT EXISTS reviewed_by UUID REFERENCES users(id);
ALTER TABLE partner_applications ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;
ALTER TABLE partner_applications ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE partner_applications ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE partner_applications ALTER COLUMN id SET DEFAULT gen_random_uuid();

-- Only one PENDING application per user; rejected users may re-apply
CREATE UNIQUE INDEX IF NOT EXISTS uq_partner_app_user_pending
    ON partner_applications(user_id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_partner_applications_status ON partner_applications(status);
CREATE INDEX IF NOT EXISTS idx_partner_applications_user   ON partner_applications(user_id);
