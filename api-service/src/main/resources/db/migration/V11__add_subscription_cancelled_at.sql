-- V11__add_subscription_cancelled_at.sql
-- Track when a user explicitly cancelled their subscription (access continues until end_date)

ALTER TABLE user_subscriptions
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;
