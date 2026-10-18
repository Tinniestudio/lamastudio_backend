package com.tinniestudio.api.modules.billing.controller;

import com.tinniestudio.api.modules.billing.service.StripeService;
import com.tinniestudio.api.modules.billing.service.SubscriptionService;
import com.tinniestudio.api.shared.web.SkipResponseWrapper;
import com.stripe.model.Event;
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
            case "setup_intent.succeeded" -> {
                Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
                if (obj.isPresent() && obj.get() instanceof com.stripe.model.SetupIntent setupIntent) {
                    subscriptionService.completeSetupFromWebhook(
                        setupIntent.getCustomer(), setupIntent.getPaymentMethod());
                }
            }
            default -> log.debug("Unhandled Stripe event type: {}", event.getType());
        }

        return ResponseEntity.ok(Map.of("received", true));
    }
}
