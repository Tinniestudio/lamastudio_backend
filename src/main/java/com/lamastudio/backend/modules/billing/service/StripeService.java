package com.lamastudio.backend.modules.billing.service;

import com.stripe.model.Event;

import java.util.Map;

/**
 * Abstraction over the Stripe SDK. Only StripeServiceImpl may import com.stripe.* classes
 * (constitution §VI — no infrastructure SDK in domain services).
 */
public interface StripeService {

    /**
     * Creates a Stripe Checkout Session (hosted payment page) for a one-time card payment.
     * Returns the session URL for frontend redirect and the payment intent ID for tracking.
     */
    CreateCheckoutResult createCheckoutSession(long amountCents, String currency, String planName,
                                               String successUrl, String cancelUrl,
                                               Map<String, String> metadata);

    Event constructWebhookEvent(String payload, String sigHeader);

    /**
     * Retrieves a Stripe Checkout Session and returns its current payment status.
     * Used to manually verify payment when a webhook was missed or delayed.
     */
    VerifySessionResult verifyCheckoutSession(String checkoutSessionId);

    record CreateCheckoutResult(String checkoutSessionId, String paymentIntentId, String checkoutUrl) {}

    record VerifySessionResult(String checkoutSessionId, String paymentIntentId, boolean paid, String status) {}
}
