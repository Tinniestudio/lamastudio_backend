package com.tinniestudio.api.modules.billing.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.billing.dto.*;
import com.tinniestudio.api.modules.billing.service.SubscriptionService;
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
            request.getCode(), CurrentUser.id(principal), request.getPlanId()));
    }

    @Operation(summary = "Get current user's subscription status and payment history")
    @GetMapping("/me")
    public ResponseEntity<SubscriptionStatusResponse> getStatus(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(subscriptionService.getSubscriptionStatus(CurrentUser.id(principal)));
    }

    @Operation(summary = "Start payment-method setup: creates/reuses the Stripe Customer and a SetupIntent")
    @PostMapping("/payment-setup/intent")
    public ResponseEntity<PaymentSetupIntentResponse> startPaymentSetup(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(subscriptionService.startPaymentSetup(CurrentUser.id(principal)));
    }

    @Operation(summary = "Confirm payment-method setup after the client has confirmed the SetupIntent")
    @PostMapping("/payment-setup/confirm")
    public ResponseEntity<SubscriptionStatusResponse> confirmPaymentSetup(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody PaymentSetupConfirmRequest request) {
        UUID userId = CurrentUser.id(principal);
        subscriptionService.confirmPaymentSetup(userId, request);
        return ResponseEntity.ok(subscriptionService.getSubscriptionStatus(userId));
    }

    @Operation(summary = "Disable auto-renewal; access continues until the current period ends")
    @PostMapping("/card/disable")
    public ResponseEntity<SubscriptionStatusResponse> disableCard(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = CurrentUser.id(principal);
        subscriptionService.disableCard(userId);
        return ResponseEntity.ok(subscriptionService.getSubscriptionStatus(userId));
    }

    @Operation(summary = "Re-enable auto-renewal with the existing saved card")
    @PostMapping("/card/enable")
    public ResponseEntity<SubscriptionStatusResponse> enableCard(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = CurrentUser.id(principal);
        subscriptionService.enableCard(userId);
        return ResponseEntity.ok(subscriptionService.getSubscriptionStatus(userId));
    }

    @Operation(summary = "Retry a failed renewal charge against the existing saved card")
    @PostMapping("/payment/retry")
    public ResponseEntity<SubscriptionStatusResponse> retryPayment(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = CurrentUser.id(principal);
        subscriptionService.retryPayment(userId);
        return ResponseEntity.ok(subscriptionService.getSubscriptionStatus(userId));
    }

    @Operation(summary = "Switch plans; takes effect at the next renewal, no proration")
    @PatchMapping("/plan")
    public ResponseEntity<SubscriptionStatusResponse> switchPlan(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody PlanSwitchRequest request) {
        UUID userId = CurrentUser.id(principal);
        subscriptionService.switchPlan(userId, request.getPlanId());
        return ResponseEntity.ok(subscriptionService.getSubscriptionStatus(userId));
    }

}
