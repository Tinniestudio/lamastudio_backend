package com.tinniestudio.api.modules.billing.service;

import com.tinniestudio.api.modules.billing.dto.CouponValidationResult;

import java.util.UUID;

public interface CouponService {

    CouponValidationResult validateCoupon(String code, UUID userId);

    void redeemCoupon(UUID couponId, UUID userId, UUID subscriptionId);
}
