-- V59__add_recurring_billing_fields.sql
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS payment_setup BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS stripe_customer_id VARCHAR(255);
ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS stripe_payment_method_id VARCHAR(255);
ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS trial_ends_at TIMESTAMPTZ;
ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS next_renewal_at TIMESTAMPTZ;
ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS pending_plan_id UUID REFERENCES subscription_plans(id);
ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS pending_coupon_id UUID REFERENCES coupons(id);
ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS pending_discount_amount NUMERIC(10, 2);

CREATE INDEX IF NOT EXISTS idx_user_subscriptions_stripe_customer_id
    ON user_subscriptions(stripe_customer_id);

ALTER TABLE payments ADD COLUMN IF NOT EXISTS type VARCHAR(20) NOT NULL DEFAULT 'ONE_TIME';
