package com.lamastudio.backend.modules.billing.controller;

import com.lamastudio.backend.modules.billing.service.StripeService;
import com.lamastudio.backend.modules.billing.service.SubscriptionService;
import com.lamastudio.backend.shared.web.SkipResponseWrapper;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.model.StripeObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@SkipResponseWrapper
@Tag(name = "Stripe Webhooks", description = "Stripe payment event handlers")
@Slf4j
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeService stripeService;
    private final SubscriptionService subscriptionService;

    @Operation(summary = "Receive Stripe webhook events (Stripe-Signature required)")
    @PostMapping(value = "/stripe", consumes = "application/json")
    public ResponseEntity<Map<String, Boolean>> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        Event event = stripeService.constructWebhookEvent(payload, sigHeader);
        log.info("Stripe webhook received: type={}, id={}", event.getType(), event.getId());

        switch (event.getType()) {
            case "checkout.session.completed" -> {
                Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
                if (obj.isPresent() && obj.get() instanceof Session session) {
                    String paymentIntentId = session.getPaymentIntent();
                    subscriptionService.activateSubscription(session.getId(), paymentIntentId);
                }
            }
            case "payment_intent.succeeded" -> {
                Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
                if (obj.isPresent() && obj.get() instanceof PaymentIntent pi) {
                    subscriptionService.activateSubscription(null, pi.getId());
                }
            }
            case "payment_intent.payment_failed" -> {
                Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
                if (obj.isPresent() && obj.get() instanceof PaymentIntent pi) {
                    String reason = pi.getLastPaymentError() != null
                        ? pi.getLastPaymentError().getMessage() : "Payment declined";
                    subscriptionService.failPayment(pi.getId(), reason);
                }
            }
            default -> log.debug("Unhandled Stripe event type: {}", event.getType());
        }

        return ResponseEntity.ok(Map.of("received", true));
    }
}
