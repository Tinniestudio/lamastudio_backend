-- Account appeal mechanism (Batch 14 #2): a suspended account can submit an appeal;
-- admin reviews it and, on approval, reactivates the account. Modeled on the
-- partner_applications shape (V36), with reviewed_by pointed straight at admins(id)
-- from the start (see V43 for why that matters).
CREATE TABLE IF NOT EXISTS account_appeals (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason      TEXT         NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reviewed_by UUID         REFERENCES admins(id),
    reviewed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE account_appeals ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE account_appeals ADD COLUMN IF NOT EXISTS reason TEXT NOT NULL;
ALTER TABLE account_appeals ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE account_appeals ADD COLUMN IF NOT EXISTS reviewed_by UUID REFERENCES admins(id);
ALTER TABLE account_appeals ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;
ALTER TABLE account_appeals ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE account_appeals ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE account_appeals ALTER COLUMN id SET DEFAULT gen_random_uuid();

-- Only one PENDING appeal per user at a time
CREATE UNIQUE INDEX IF NOT EXISTS uq_account_appeal_user_pending
    ON account_appeals(user_id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_account_appeals_status ON account_appeals(status);
CREATE INDEX IF NOT EXISTS idx_account_appeals_user   ON account_appeals(user_id);
