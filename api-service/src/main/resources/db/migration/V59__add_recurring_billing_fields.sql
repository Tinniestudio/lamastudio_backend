-- V59__add_recurring_billing_fields.sql
ALTER TABLE user_profiles ADD COLUMN payment_setup BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE user_subscriptions ADD COLUMN stripe_customer_id VARCHAR(255);
ALTER TABLE user_subscriptions ADD COLUMN stripe_payment_method_id VARCHAR(255);
ALTER TABLE user_subscriptions ADD COLUMN trial_ends_at TIMESTAMP;
ALTER TABLE user_subscriptions ADD COLUMN next_renewal_at TIMESTAMP;
ALTER TABLE user_subscriptions ADD COLUMN pending_plan_id UUID;
ALTER TABLE user_subscriptions ADD COLUMN pending_coupon_id UUID;
ALTER TABLE user_subscriptions ADD COLUMN pending_discount_amount NUMERIC(10, 2);

ALTER TABLE payments ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'ONE_TIME';
