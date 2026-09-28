package com.tinniestudio.api.modules.billing.service;

import com.tinniestudio.api.shared.config.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionRetrieveParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeServiceImpl implements StripeService {

    /**
     * 30 minutes is Stripe's own hard minimum for Checkout Session expires_at (validated
     * server-side by Stripe's API — a shorter value throws InvalidRequestException). This is the
     * shortest deadline Stripe allows, matching the Payment Session Expiry design's intent of a
     * short-lived session even though its literal "15 minutes" isn't achievable.
     */
    private static final long SESSION_EXPIRY_MINUTES = 30;

    private final StripeProperties stripeProperties;

    @Override
    public CreateCheckoutResult createCheckoutSession(long amountCents, String currency, String planName,
                                                      String successUrl, String cancelUrl,
                                                      Map<String, String> metadata) {
        try {
            Instant expiresAt = Instant.now().plus(SESSION_EXPIRY_MINUTES, ChronoUnit.MINUTES);

            SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .addLineItem(SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(currency.toLowerCase())
                        .setUnitAmount(amountCents)
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                            .setName(planName + " Subscription")
                            .build())
                        .build())
                    .build())
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setExpiresAt(expiresAt.getEpochSecond());

            metadata.forEach(paramsBuilder::putMetadata);

            Session session = Session.create(paramsBuilder.build());
            log.info("Stripe Checkout Session created: id={}, expiresAt={}", session.getId(), expiresAt);
            return new CreateCheckoutResult(session.getId(), session.getPaymentIntent(), session.getUrl(), expiresAt);

        } catch (StripeException ex) {
            log.error("Failed to create Stripe Checkout Session: {}", ex.getMessage(), ex);
            throw new RuntimeException("Payment provider error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public VerifySessionResult verifyCheckoutSession(String checkoutSessionId) {
        try {
            Session session = Session.retrieve(
                    checkoutSessionId,
                    SessionRetrieveParams.builder().build(),
                    null
            );
            String paymentIntentId = session.getPaymentIntent();
            boolean paid = "paid".equals(session.getPaymentStatus())
                    || "complete".equals(session.getStatus());
            log.info("Stripe session {} status={} paymentStatus={}", checkoutSessionId,
                    session.getStatus(), session.getPaymentStatus());
            return new VerifySessionResult(checkoutSessionId, paymentIntentId, paid, session.getStatus());
        } catch (StripeException ex) {
            log.error("Failed to retrieve Stripe session {}: {}", checkoutSessionId, ex.getMessage(), ex);
            throw new RuntimeException("Payment provider error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Event constructWebhookEvent(String payload, String sigHeader) {
        try {
            return Webhook.constructEvent(payload, sigHeader, stripeProperties.getWebhookSecret());
        } catch (SignatureVerificationException ex) {
            log.warn("Invalid Stripe webhook signature: {}", ex.getMessage());
            throw new com.tinniestudio.api.shared.exception.BadRequestException("Invalid webhook signature");
        } catch (Exception ex) {
            log.error("Failed to construct Stripe webhook event: {}", ex.getMessage(), ex);
            throw new com.tinniestudio.api.shared.exception.BadRequestException("Invalid webhook event");
        }
    }
}
