-- V10__add_payments.sql
-- Stripe payment transaction records (card-only, one-time Payment Intents)

CREATE TABLE payments (
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

CREATE INDEX idx_payments_user_id         ON payments(user_id);
CREATE INDEX idx_payments_subscription_id ON payments(subscription_id);
CREATE INDEX idx_payments_status          ON payments(status);
