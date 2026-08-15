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

-- Only one PENDING appeal per user at a time
CREATE UNIQUE INDEX uq_account_appeal_user_pending
    ON account_appeals(user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_account_appeals_status ON account_appeals(status);
CREATE INDEX idx_account_appeals_user   ON account_appeals(user_id);
