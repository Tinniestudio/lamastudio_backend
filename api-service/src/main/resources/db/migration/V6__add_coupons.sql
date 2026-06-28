-- V5__add_coupons.sql
-- Coupon system for subscription discounts

CREATE TABLE coupons (
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

CREATE TABLE coupon_redemptions (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id        UUID        NOT NULL REFERENCES coupons(id),
    user_id          UUID        NOT NULL REFERENCES users(id),
    subscription_id  UUID        NOT NULL REFERENCES user_subscriptions(id),
    redeemed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (coupon_id, user_id)
);

CREATE INDEX idx_coupons_code      ON coupons(LOWER(code));
CREATE INDEX idx_coupons_active    ON coupons(is_active) WHERE is_active = TRUE;
CREATE INDEX idx_redemptions_user  ON coupon_redemptions(user_id);
