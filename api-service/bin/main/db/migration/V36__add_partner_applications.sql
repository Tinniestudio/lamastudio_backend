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

-- Only one PENDING application per user; rejected users may re-apply
CREATE UNIQUE INDEX uq_partner_app_user_pending
    ON partner_applications(user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_partner_applications_status ON partner_applications(status);
CREATE INDEX idx_partner_applications_user   ON partner_applications(user_id);
