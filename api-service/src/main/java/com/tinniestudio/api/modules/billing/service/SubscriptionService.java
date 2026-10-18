package com.tinniestudio.api.modules.billing.service;

import com.tinniestudio.api.modules.billing.dto.*;

import java.util.List;
import java.util.UUID;

public interface SubscriptionService {

    List<PlanResponse> listPlans();

    CouponValidationResponse validateCoupon(String code, UUID userId, UUID planId);

    PaymentSetupIntentResponse startPaymentSetup(UUID userId);

    void confirmPaymentSetup(UUID userId, PaymentSetupConfirmRequest request);

    void disableCard(UUID userId);

    void enableCard(UUID userId);

    void retryPayment(UUID userId);

    void switchPlan(UUID userId, UUID newPlanId);

    SubscriptionStatusResponse getSubscriptionStatus(UUID userId);

    /** Called by RecurringBillingRenewalJob for each subscription due for renewal. */
    void processRenewal(UUID subscriptionId);
}
