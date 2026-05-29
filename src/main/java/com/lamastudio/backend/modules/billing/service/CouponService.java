package com.lamastudio.backend.modules.billing.service;

import com.lamastudio.backend.modules.billing.dto.CouponValidationResult;

import java.util.UUID;

public interface CouponService {

    CouponValidationResult validateCoupon(String code, UUID userId);

    void redeemCoupon(UUID couponId, UUID userId, UUID subscriptionId);
}
