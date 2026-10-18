package com.tinniestudio.api.modules.billing.service;

import com.stripe.model.Event;

import java.util.Map;

/**
 * Abstraction over the Stripe SDK. Only StripeServiceImpl may import com.stripe.* classes
 * (constitution §VI — no infrastructure SDK in domain services).
 */
public interface StripeService {

    /** Creates a Stripe Customer, returning its id. */
    String createCustomer(String email, Map<String, String> metadata);

    /** Creates a SetupIntent for the given customer, for client-side card collection. */
    CreateSetupIntentResult createSetupIntent(String customerId);

    /** Retrieves a SetupIntent's resolved customer/payment-method ids and status. */
    VerifySetupIntentResult retrieveSetupIntent(String setupIntentId);

    /** Resolves the userId stored in a Stripe Customer's metadata at creation time (see
     * createCustomer) — used to verify a SetupIntent's resolved customer genuinely belongs to the
     * user confirming it, rather than trusting a client-supplied setupIntentId at face value.
     * Empty if the customer has no userId metadata (shouldn't happen for customers this service
     * created, but a missing/unexpected value must fail closed, never be treated as a match). */
    java.util.Optional<String> resolveCustomerOwnerUserId(String customerId);

    /**
     * Charges a saved payment method off-session (no customer present). Declines and
     * authentication-required outcomes are returned as a failed {@link ChargeResult}, never
     * thrown — these are expected business outcomes, not infrastructure errors.
     */
    ChargeResult chargeOffSession(String customerId, String paymentMethodId, long amountCents,
                                   String currency, String idempotencyKey);

    Event constructWebhookEvent(String payload, String sigHeader);

    record CreateSetupIntentResult(String setupIntentId, String clientSecret) {}

    record VerifySetupIntentResult(String setupIntentId, String customerId, String paymentMethodId, boolean succeeded) {}

    record ChargeResult(String paymentIntentId, boolean succeeded, String failureReason) {}
}
