-- V44__add_coupons_uses_count_guard.sql
-- Guard against uses_count ever exceeding max_uses at the database level.
-- This complements (does not replace) the atomic conditional UPDATE used in
-- CouponServiceImpl.redeemCoupon, which is the actual fix for the lost-update
-- race on coupon redemption (two concurrent redemptions both reading
-- uses_count < max_uses before either commits). The CHECK constraint below is
-- a defense-in-depth belt-and-suspenders guarantee: even if a future code path
-- reintroduces a non-atomic read-then-write, the database will reject the
-- write outright instead of silently over-redeeming the coupon.

ALTER TABLE coupons
    ADD CONSTRAINT chk_coupons_uses_count_within_max_uses
    CHECK (max_uses IS NULL OR uses_count <= max_uses);
