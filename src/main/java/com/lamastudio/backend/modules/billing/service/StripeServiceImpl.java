package com.lamastudio.backend.modules.billing.service;

import com.lamastudio.backend.shared.config.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeServiceImpl implements StripeService {

    private final StripeProperties stripeProperties;

    @Override
    public CreateCheckoutResult createCheckoutSession(long amountCents, String currency, String planName,
                                                      String successUrl, String cancelUrl,
                                                      Map<String, String> metadata) {
        try {
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
                .setCancelUrl(cancelUrl);

            metadata.forEach(paramsBuilder::putMetadata);

            Session session = Session.create(paramsBuilder.build());
            log.info("Stripe Checkout Session created: id={}", session.getId());
            return new CreateCheckoutResult(session.getId(), session.getPaymentIntent(), session.getUrl());

        } catch (StripeException ex) {
            log.error("Failed to create Stripe Checkout Session: {}", ex.getMessage(), ex);
            throw new RuntimeException("Payment provider error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Event constructWebhookEvent(String payload, String sigHeader) {
        try {
            return Webhook.constructEvent(payload, sigHeader, stripeProperties.getWebhookSecret());
        } catch (SignatureVerificationException ex) {
            log.warn("Invalid Stripe webhook signature: {}", ex.getMessage());
            throw new com.lamastudio.backend.shared.exception.BadRequestException("Invalid webhook signature");
        } catch (Exception ex) {
            log.error("Failed to construct Stripe webhook event: {}", ex.getMessage(), ex);
            throw new com.lamastudio.backend.shared.exception.BadRequestException("Invalid webhook event");
        }
    }
}
