package com.tinniestudio.api.modules.billing.service;

import com.tinniestudio.api.shared.config.StripeProperties;
import com.stripe.exception.CardException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.SetupIntentCreateParams;
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
    public String createCustomer(String email, Map<String, String> metadata) {
        try {
            CustomerCreateParams.Builder builder = CustomerCreateParams.builder().setEmail(email);
            metadata.forEach(builder::putMetadata);
            Customer customer = Customer.create(builder.build());
            log.info("Stripe Customer created: id={}", customer.getId());
            return customer.getId();
        } catch (StripeException ex) {
            log.error("Failed to create Stripe Customer: {}", ex.getMessage(), ex);
            throw new RuntimeException("Payment provider error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public CreateSetupIntentResult createSetupIntent(String customerId) {
        try {
            SetupIntentCreateParams params = SetupIntentCreateParams.builder()
                .setCustomer(customerId)
                .addPaymentMethodType("card")
                .build();
            SetupIntent setupIntent = SetupIntent.create(params);
            return new CreateSetupIntentResult(setupIntent.getId(), setupIntent.getClientSecret());
        } catch (StripeException ex) {
            log.error("Failed to create SetupIntent for customer {}: {}", customerId, ex.getMessage(), ex);
            throw new RuntimeException("Payment provider error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public VerifySetupIntentResult retrieveSetupIntent(String setupIntentId) {
        try {
            SetupIntent setupIntent = SetupIntent.retrieve(setupIntentId);
            return new VerifySetupIntentResult(
                setupIntent.getId(),
                setupIntent.getCustomer(),
                setupIntent.getPaymentMethod(),
                "succeeded".equals(setupIntent.getStatus()));
        } catch (StripeException ex) {
            log.error("Failed to retrieve SetupIntent {}: {}", setupIntentId, ex.getMessage(), ex);
            throw new RuntimeException("Payment provider error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public ChargeResult chargeOffSession(String customerId, String paymentMethodId, long amountCents,
                                          String currency, String idempotencyKey) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setCustomer(customerId)
                .setPaymentMethod(paymentMethodId)
                .setAmount(amountCents)
                .setCurrency(currency.toLowerCase())
                .setOffSession(true)
                .setConfirm(true)
                .build();
            RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();
            PaymentIntent paymentIntent = PaymentIntent.create(params, options);
            log.info("Off-session charge succeeded: paymentIntentId={}", paymentIntent.getId());
            return new ChargeResult(paymentIntent.getId(), true, null);
        } catch (CardException ex) {
            log.warn("Off-session charge declined for customer {}: {}", customerId, ex.getMessage());
            return new ChargeResult(null, false, ex.getMessage());
        } catch (StripeException ex) {
            log.error("Off-session charge failed for customer {}: {}", customerId, ex.getMessage(), ex);
            return new ChargeResult(null, false, ex.getMessage());
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
