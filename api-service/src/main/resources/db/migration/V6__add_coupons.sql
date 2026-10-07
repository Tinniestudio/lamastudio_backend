-- V5__add_coupons.sql
-- Coupon system for subscription discounts

CREATE TABLE IF NOT EXISTS coupons (
    id                   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    code                 VARCHAR(50)    NOT NULL UNIQUE,
    discount_type        VARCHAR(20)    NOT NULL,
    discount_value       DECIMAL(10,2)  NOT NULL,
    currency             VARCHAR(3),
    max_uses             INT,
    uses_count           INT            NOT NULL DEFAULT 0,
    valid_from           TIMESTAMPTZ,
    valid_until          TIMESTAMPTZ,
    is_active            BOOLEAN        NOT NULL DEFAULT TRUE,
    created_by_admin_id  UUID           REFERENCES admins(id),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS code VARCHAR(50) NOT NULL UNIQUE;
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS discount_type VARCHAR(20) NOT NULL;
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS discount_value DECIMAL(10,2) NOT NULL;
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS currency VARCHAR(3);
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS max_uses INT;
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS uses_count INT NOT NULL DEFAULT 0;
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS valid_from TIMESTAMPTZ;
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS valid_until TIMESTAMPTZ;
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS created_by_admin_id UUID REFERENCES admins(id);
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE TABLE IF NOT EXISTS coupon_redemptions (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id        UUID        NOT NULL REFERENCES coupons(id),
    user_id          UUID        NOT NULL REFERENCES users(id),
    subscription_id  UUID        NOT NULL REFERENCES user_subscriptions(id),
    redeemed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (coupon_id, user_id)
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table
-- already pre-existed (see V16/V18 production drift fix for why).
ALTER TABLE coupon_redemptions ADD COLUMN IF NOT EXISTS coupon_id UUID NOT NULL REFERENCES coupons(id);
ALTER TABLE coupon_redemptions ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL REFERENCES users(id);
ALTER TABLE coupon_redemptions ADD COLUMN IF NOT EXISTS subscription_id UUID NOT NULL REFERENCES user_subscriptions(id);
ALTER TABLE coupon_redemptions ADD COLUMN IF NOT EXISTS redeemed_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_coupons_code      ON coupons(LOWER(code));
CREATE INDEX IF NOT EXISTS idx_coupons_active    ON coupons(is_active) WHERE is_active = TRUE;
CREATE INDEX IF NOT EXISTS idx_redemptions_user  ON coupon_redemptions(user_id);
