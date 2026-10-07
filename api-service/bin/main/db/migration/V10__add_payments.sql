-- V10__add_payments.sql
-- Stripe payment transaction records (card-only, one-time Payment Intents)

CREATE TABLE IF NOT EXISTS payments (
    id                   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID           NOT NULL REFERENCES users(id),
    subscription_id      UUID           REFERENCES user_subscriptions(id),
    plan_id              UUID           NOT NULL REFERENCES subscription_plans(id),
    provider             VARCHAR(20)    NOT NULL DEFAULT 'STRIPE',
    provider_reference   VARCHAR(255)   NOT NULL UNIQUE,
    amount               DECIMAL(10,2)  NOT NULL,
    currency             VARCHAR(3)     NOT NULL,
    status               VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    auto_renew           BOOLEAN        NOT NULL DEFAULT TRUE,
    coupon_id            UUID           REFERENCES coupons(id),
    discount_amount      DECIMAL(10,2),
    paid_at              TIMESTAMPTZ,
    failure_reason       TEXT,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE payments ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS subscription_id UUID REFERENCES user_subscriptions(id);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS plan_id UUID NOT NULL REFERENCES subscription_plans(id);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS provider VARCHAR(20) NOT NULL DEFAULT 'STRIPE';
ALTER TABLE payments ADD COLUMN IF NOT EXISTS provider_reference VARCHAR(255) NOT NULL UNIQUE;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS amount DECIMAL(10,2) NOT NULL;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS currency VARCHAR(3) NOT NULL;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE payments ADD COLUMN IF NOT EXISTS auto_renew BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS coupon_id UUID REFERENCES coupons(id);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS discount_amount DECIMAL(10,2);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS paid_at TIMESTAMPTZ;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS failure_reason TEXT;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_payments_user_id         ON payments(user_id);
CREATE INDEX IF NOT EXISTS idx_payments_subscription_id ON payments(subscription_id);
CREATE INDEX IF NOT EXISTS idx_payments_status          ON payments(status);
