package com.lamastudio.backend.modules.billing.controller;

import com.lamastudio.backend.modules.billing.dto.*;
import com.lamastudio.backend.modules.billing.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Subscriptions", description = "Subscription plans and billing")
@RestController
@RequestMapping("/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Operation(summary = "List purchasable plans (SILVER and GOLD only — FREE is system-assigned)")
    @GetMapping("/plans")
    public ResponseEntity<List<PlanResponse>> listPlans() {
        return ResponseEntity.ok(subscriptionService.listPlans());
    }

    @Operation(summary = "Validate a coupon code against a plan (no charge)")
    @PostMapping("/apply-coupon")
    public ResponseEntity<CouponValidationResponse> validateCoupon(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody CouponValidationRequest request) {
        return ResponseEntity.ok(subscriptionService.validateCoupon(
            request.getCode(), userId(principal), request.getPlanId()));
    }

    @Operation(summary = "Initiate Stripe Checkout for a subscription plan")
    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.ok(subscriptionService.initiateCheckout(userId(principal), request));
    }

    @Operation(summary = "Get current user's subscription status and payment history")
    @GetMapping("/me")
    public ResponseEntity<SubscriptionStatusResponse> getStatus(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(subscriptionService.getSubscriptionStatus(userId(principal)));
    }

    @Operation(summary = "Cancel active subscription (access continues until end date)")
    @PatchMapping("/cancel")
    public ResponseEntity<SubscriptionStatusResponse> cancel(
            @AuthenticationPrincipal UserDetails principal) {
        subscriptionService.cancelSubscription(userId(principal));
        return ResponseEntity.ok(subscriptionService.getSubscriptionStatus(userId(principal)));
    }

    @Operation(summary = "Verify a pending payment against Stripe — use when webhook delivery was missed")
    @PostMapping("/verify-payment/{paymentReference}")
    public ResponseEntity<SubscriptionStatusResponse> verifyPayment(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable String paymentReference) {
        return ResponseEntity.ok(subscriptionService.verifyPayment(userId(principal), paymentReference));
    }

    private UUID userId(UserDetails principal) {
        return UUID.fromString(principal.getUsername());
    }
}
