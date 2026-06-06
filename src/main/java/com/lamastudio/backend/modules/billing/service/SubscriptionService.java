package com.lamastudio.backend.modules.billing.service;

import com.lamastudio.backend.modules.billing.dto.*;

import java.util.List;
import java.util.UUID;

public interface SubscriptionService {

    List<PlanResponse> listPlans();

    CouponValidationResponse validateCoupon(String code, UUID userId, UUID planId);

    CheckoutResponse initiateCheckout(UUID userId, CheckoutRequest request);

    void activateSubscription(String checkoutSessionId, String paymentIntentId);

    void failPayment(String paymentIntentId, String reason);

    SubscriptionStatusResponse getSubscriptionStatus(UUID userId);

    void cancelSubscription(UUID userId);

    SubscriptionStatusResponse verifyPayment(UUID userId, UUID paymentId);
}
