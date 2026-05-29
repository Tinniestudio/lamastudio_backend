-- V5__add_subscription_tables.sql
-- Subscription plans and user subscriptions (previously Hibernate-managed, now Flyway-owned).
-- Uses IF NOT EXISTS throughout so this migration is safe on both fresh databases
-- and databases where Hibernate's ddl-auto already created these tables.

CREATE TABLE IF NOT EXISTS subscription_plans (
    id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100)   NOT NULL,
    description   TEXT,
    price         DECIMAL(10,2)  NOT NULL,
    currency      VARCHAR(3),
    billing_cycle VARCHAR(20),
    max_devices   INT,
    video_quality VARCHAR(20),
    content_limit INT,
    is_active     BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Guard: if the table pre-existed (Hibernate-created), ensure all columns we need are present.
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS content_limit INT;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS max_devices   INT;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS billing_cycle VARCHAR(20);
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS video_quality VARCHAR(20);
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE TABLE IF NOT EXISTS user_subscriptions (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_id              UUID        REFERENCES subscription_plans(id),
    status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    start_date           TIMESTAMPTZ,
    end_date             TIMESTAMPTZ,
    auto_renew           BOOLEAN     NOT NULL DEFAULT TRUE,
    content_watches_used INT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Guard: ensure content_watches_used exists on pre-existing user_subscriptions tables.
ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS content_watches_used INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_user_subscriptions_user_id ON user_subscriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_user_subscriptions_status  ON user_subscriptions(user_id, status);

-- Seed default subscription plans.
-- All NOT NULL columns are provided explicitly because the pre-existing Hibernate-managed table
-- has no DB-side DEFAULTs on id, created_at, or updated_at — Hibernate sets these in Java via
-- @GeneratedValue, @CreationTimestamp, and @UpdateTimestamp respectively.
INSERT INTO subscription_plans (id, name, description, price, currency, billing_cycle, max_devices, video_quality, content_limit, is_active, created_at, updated_at)
SELECT gen_random_uuid(), 'FREE',   'Free tier with limited access', 0.00, 'USD', 'MONTHLY', 1, 'SD',      2,    true, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM subscription_plans WHERE name = 'FREE');

INSERT INTO subscription_plans (id, name, description, price, currency, billing_cycle, max_devices, video_quality, content_limit, is_active, created_at, updated_at)
SELECT gen_random_uuid(), 'SILVER', 'Silver plan',                   9.99, 'USD', 'MONTHLY', 1, 'HD',      NULL, true, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM subscription_plans WHERE name = 'SILVER');

INSERT INTO subscription_plans (id, name, description, price, currency, billing_cycle, max_devices, video_quality, content_limit, is_active, created_at, updated_at)
SELECT gen_random_uuid(), 'GOLD',   'Gold plan - multi-device',     19.99, 'USD', 'MONTHLY', 3, 'FULL_HD', NULL, true, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM subscription_plans WHERE name = 'GOLD');

-- Triggers (guarded with DROP IF EXISTS to handle pre-existing triggers from Hibernate sessions).
DROP TRIGGER IF EXISTS trg_subscription_plans_updated_at ON subscription_plans;
CREATE TRIGGER trg_subscription_plans_updated_at
    BEFORE UPDATE ON subscription_plans
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS trg_user_subscriptions_updated_at ON user_subscriptions;
CREATE TRIGGER trg_user_subscriptions_updated_at
    BEFORE UPDATE ON user_subscriptions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
