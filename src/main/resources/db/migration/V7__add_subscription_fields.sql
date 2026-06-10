-- V6__add_subscription_fields.sql
-- Add content quota fields to billing entities

ALTER TABLE subscription_plans
    ADD COLUMN IF NOT EXISTS content_limit INT;

ALTER TABLE user_subscriptions
    ADD COLUMN IF NOT EXISTS content_watches_used INT NOT NULL DEFAULT 0;
