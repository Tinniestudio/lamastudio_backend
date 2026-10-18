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

    /** Safety net for setup_intent.succeeded: resolves which user owns stripeCustomerId and applies
     * the same effect as confirmPaymentSetup's existing-subscription branch (saves the payment
     * method, re-triggers a renewal if the period had expired or the last one failed). No-ops
     * (just logs) if no subscription is found for that customer yet — this only covers the
     * retry-with-new-card case, not first-ever setup, since there's no plan context available from
     * the webhook alone. Also no-ops if paymentMethodId already matches what's stored, but doesn't
     * otherwise guard against Stripe delivering two setup_intent.succeeded events out of order (e.g.
     * two cards added back-to-back) — rare in practice since this is a fallback path, not the
     * primary confirmPaymentSetup flow, but a known limitation if it ever needs hardening. */
    void completeSetupFromWebhook(String stripeCustomerId, String paymentMethodId);
}
