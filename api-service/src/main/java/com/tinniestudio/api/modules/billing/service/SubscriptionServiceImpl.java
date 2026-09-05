package com.tinniestudio.api.modules.billing.service;

import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.modules.billing.dto.*;
import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.billing.repository.SubscriptionPlanRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private final SubscriptionPlanRepository planRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final PaymentRepository paymentRepository;
    private final com.tinniestudio.api.modules.user.repository.UserRepository userRepository;
    private final CouponService couponService;
    private final StripeService stripeService;
    private final EmailService emailService;
    private final AppProperties appProperties;

    @Override
    @Transactional(readOnly = true)
    public List<PlanResponse> listPlans() {
        return planRepository.findByNameInAndIsActiveTrue(List.of("SILVER", "GOLD"))
                .stream()
                .map(PlanResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public CouponValidationResponse validateCoupon(String code, UUID userId, UUID planId) {
        SubscriptionPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));

        CouponValidationResult result = couponService.validateCoupon(code, userId);
        if (!result.isValid()) {
            throw new BadRequestException(reasonMessage(result.getReason()));
        }

        BigDecimal originalPrice = plan.getPrice();
        BigDecimal finalPrice = applyDiscount(originalPrice, result);

        return CouponValidationResponse.builder()
                .valid(true)
                .couponId(result.getCouponId())
                .discountType(result.getDiscountType())
                .discountValue(result.getDiscountValue())
                .originalPrice(originalPrice)
                .finalPrice(finalPrice)
                .currency(plan.getCurrency())
                .build();
    }

    @Override
    @Transactional
    public CheckoutResponse initiateCheckout(UUID userId, CheckoutRequest request) {
        // Reject if user already has an active paid subscription
        boolean hasActivePaid = subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .map(s -> s.getPlan() != null && !"FREE".equalsIgnoreCase(s.getPlan().getName()))
                .orElse(false);
        if (hasActivePaid) {
            throw new BadRequestException("You already have an active subscription");
        }

        SubscriptionPlan plan = planRepository.findById(request.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));

        if (!"SILVER".equalsIgnoreCase(plan.getName()) && !"GOLD".equalsIgnoreCase(plan.getName())) {
            throw new BadRequestException("Plan is not available for purchase");
        }

        BigDecimal finalAmount = plan.getPrice();
        UUID couponId = null;
        BigDecimal discountAmount = null;

        if (request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
            CouponValidationResult coupon = couponService.validateCoupon(request.getCouponCode(), userId);
            if (!coupon.isValid()) {
                throw new BadRequestException(reasonMessage(coupon.getReason()));
            }
            discountAmount = finalAmount.subtract(applyDiscount(finalAmount, coupon));
            finalAmount = applyDiscount(finalAmount, coupon);
            couponId = coupon.getCouponId();
        }

        long amountCents = finalAmount.multiply(BigDecimal.valueOf(100)).longValue();
        String frontendUrl = appProperties.getFrontendUrl();
        String successUrl = frontendUrl + "/checkout/success?session_id={CHECKOUT_SESSION_ID}";
        String cancelUrl = frontendUrl + "/checkout/cancelled";

        Payment payment = new Payment();
        // Pre-generate id so Stripe metadata can include it before persistence.
        payment.setId(UUID.randomUUID());
        payment.setUserId(userId);
        payment.setPlanId(plan.getId());
        payment.setAmount(finalAmount);
        payment.setCurrency(plan.getCurrency() != null ? plan.getCurrency() : "CAD");
        payment.setAutoRenew(Boolean.TRUE.equals(request.getAutoRenew()));
        payment.setCouponId(couponId);
        payment.setDiscountAmount(discountAmount);
        payment.setStatus(PaymentStatus.PENDING);

        StripeService.CreateCheckoutResult checkout = stripeService.createCheckoutSession(
                amountCents,
                payment.getCurrency(),
                plan.getName(),
                successUrl,
                cancelUrl,
                Map.of("paymentId", payment.getId().toString(), "userId", userId.toString()));

        payment.setProviderReference(checkout.paymentIntentId() != null
                ? checkout.paymentIntentId()
                : checkout.checkoutSessionId());
        paymentRepository.save(payment);

        return CheckoutResponse.builder()
                .paymentId(payment.getId())
                .paymentReference(checkout.checkoutSessionId())
                .paymentUrl(checkout.checkoutUrl())
                .amount(finalAmount)
                .currency(payment.getCurrency())
                .planName(plan.getName())
                .autoRenew(payment.isAutoRenew())
                .build();
    }

    @Override
    @Transactional
    public void activateSubscription(String checkoutSessionId, String paymentIntentId) {
        // Idempotency: skip if already processed
        String ref = paymentIntentId != null ? paymentIntentId : checkoutSessionId;
        if (paymentRepository.existsByProviderReferenceAndStatus(ref, PaymentStatus.SUCCESSFUL)) {
            log.info("Subscription already activated for reference={}, skipping", ref);
            return;
        }

        Payment payment = paymentRepository.findByProviderReference(ref)
                .orElseGet(() -> paymentRepository.findByProviderReference(checkoutSessionId)
                        .orElseThrow(() -> new ResourceNotFoundException("Payment not found for ref=" + ref)));

        payment.setStatus(PaymentStatus.SUCCESSFUL);
        payment.setPaidAt(Instant.now());
        if (paymentIntentId != null && !paymentIntentId.equals(payment.getProviderReference())) {
            payment.setProviderReference(paymentIntentId);
        }

        SubscriptionPlan plan = planRepository.findById(payment.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));

        Instant now = Instant.now();
        Instant endDate = "YEARLY".equalsIgnoreCase(
                plan.getBillingCycle() != null ? plan.getBillingCycle().name() : "MONTHLY")
                        ? now.plus(365, ChronoUnit.DAYS)
                        : now.plus(30, ChronoUnit.DAYS);

        // Deactivate any existing FREE subscription
        subscriptionRepository.findByUserIdAndStatus(payment.getUserId(), SubscriptionStatus.ACTIVE)
                .ifPresent(existing -> {
                    existing.setStatus(SubscriptionStatus.EXPIRED);
                    subscriptionRepository.save(existing);
                });

        UserSubscription subscription = new UserSubscription();
        subscription.setUserId(payment.getUserId());
        subscription.setPlan(plan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setAutoRenew(payment.isAutoRenew());
        subscription.setStartDate(now);
        subscription.setEndDate(endDate);
        subscription.setContentWatchesUsed(0);
        // Snapshot plan limits at activation time so future plan edits don't affect existing subscribers
        subscription.setMaxDevices(plan.getMaxDevices() != null ? plan.getMaxDevices() : 1);
        subscription = subscriptionRepository.save(subscription);

        payment.setSubscriptionId(subscription.getId());

        if (payment.getCouponId() != null) {
            try {
                couponService.redeemCoupon(payment.getCouponId(), payment.getUserId(), subscription.getId());
            } catch (Exception ex) {
                log.warn("Coupon redemption failed for couponId={}: {}", payment.getCouponId(), ex.getMessage());
            }
        }
        paymentRepository.save(payment);

        // Send activation email (respects notificationEmail preference via
        // EmailService)
        notifyUser(payment.getUserId(), plan.getName(), true, null);
        log.info("Subscription activated: userId={}, plan={}", payment.getUserId(), plan.getName());
    }

    @Override
    @Transactional
    public void failPayment(String paymentIntentId, String reason) {
        paymentRepository.findByProviderReference(paymentIntentId).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(reason);
            paymentRepository.save(payment);
            SubscriptionPlan plan = planRepository.findById(payment.getPlanId()).orElse(null);
            notifyUser(payment.getUserId(), plan != null ? plan.getName() : "Unknown", false, reason);
            log.info("Payment failed: paymentIntentId={}, reason={}", paymentIntentId, reason);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionStatusResponse getSubscriptionStatus(UUID userId) {
        UserSubscription sub = subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .or(() -> subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId))
                .orElse(null);

        List<SubscriptionStatusResponse.PaymentSummary> paymentHistory = paymentRepository
                .findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(p -> SubscriptionStatusResponse.PaymentSummary.builder()
                        .paymentId(p.getId())
                        .paymentReference(p.getProviderReference())
                        .amount(p.getAmount())
                        .currency(p.getCurrency())
                        .status(p.getStatus().name())
                        .paidAt(p.getPaidAt())
                        .build())
                .collect(Collectors.toList());

        if (sub == null) {
            return SubscriptionStatusResponse.builder()
                    .plan("FREE").status("ACTIVE").autoRenew(false)
                    .maxDevices(1).contentWatchesUsed(0).contentWatchesLimit(2)
                    .payments(paymentHistory).build();
        }

        // Treat subscription as expired if end date has passed, regardless of stored status
        Instant now = Instant.now();
        boolean periodExpired = sub.getEndDate() != null && sub.getEndDate().isBefore(now);
        String effectiveStatus = periodExpired ? SubscriptionStatus.EXPIRED.name() : sub.getStatus().name();

        SubscriptionPlan subPlan = sub.getPlan();
        String planName = (subPlan != null && !periodExpired) ? subPlan.getName() : "FREE";

        // Use snapshotted maxDevices; fall back to 1 if the migration backfill didn't cover it
        int maxDevices = periodExpired ? 1 : (sub.getMaxDevices() != null ? sub.getMaxDevices() : 1);

        // For active paid plans, null contentLimit means unlimited — only fall back to
        // the FREE limit (2) when the subscription is expired or on the FREE plan.
        boolean isFreeOrExpired = periodExpired || subPlan == null
                || "FREE".equalsIgnoreCase(subPlan.getName());
        // Integer.valueOf(2) keeps both ternary branches as Integer, preventing auto-unbox of a null contentLimit
        Integer contentLimit = isFreeOrExpired ? Integer.valueOf(2) : subPlan.getContentLimit();

        return SubscriptionStatusResponse.builder()
                .subscriptionId(sub.getId())
                .plan(planName)
                .status(effectiveStatus)
                .startDate(sub.getStartDate())
                .endDate(sub.getEndDate())
                .autoRenew(Boolean.TRUE.equals(sub.getAutoRenew()))
                .cancelledAt(sub.getCancelledAt() != null ? sub.getCancelledAt() : null)
                .maxDevices(maxDevices)
                .contentWatchesUsed(sub.getContentWatchesUsed())
                .contentWatchesLimit(contentLimit)
                .payments(paymentHistory)
                .build();
    }

    @Override
    @Transactional
    public void cancelSubscription(UUID userId) {
        UserSubscription sub = subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .filter(s -> s.getPlan() != null && !"FREE".equalsIgnoreCase(s.getPlan().getName()))
                .orElseThrow(() -> new ResourceNotFoundException("No active paid subscription found"));

        if (sub.getCancelledAt() != null) {
            throw new BadRequestException("Subscription is already cancelled");
        }

        sub.setAutoRenew(false);
        sub.setCancelledAt(Instant.now());
        subscriptionRepository.save(sub);

        notifyUser(userId, sub.getPlan().getName(), null, null);
        log.info("Subscription cancelled: userId={}, endDate={}", userId, sub.getEndDate());
    }

    @Override
    @Transactional
    public SubscriptionStatusResponse cancelPayment(UUID userId) {
        Payment payment = paymentRepository.findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, PaymentStatus.PENDING)
                .orElseThrow(() -> new ResourceNotFoundException("No pending payment found"));
    
        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setCancelledAt(Instant.now());
        paymentRepository.save(payment);
        log.info("Pending payment cancelled: userId={}, paymentId={}", userId, payment.getId());

        return getSubscriptionStatus(userId);
    }

    @Override
    @Transactional
    public SubscriptionStatusResponse verifyPayment(UUID userId, String paymentReference) {
        Payment payment = paymentRepository.findByProviderReferenceAndUserId(paymentReference, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if (payment.getStatus() == PaymentStatus.SUCCESSFUL) {
            log.info("Payment {} already successful, skipping Stripe verification", paymentReference);
            return getSubscriptionStatus(userId);
        }

        if (payment.getStatus() == PaymentStatus.FAILED) {
            throw new BadRequestException("Payment has already failed and cannot be recovered");
        }

        String ref = payment.getProviderReference();
        if (ref == null || ref.isBlank()) {
            throw new BadRequestException("No payment reference found for this payment");
        }

        StripeService.VerifySessionResult result = stripeService.verifyCheckoutSession(ref);

        log.info("Stripe verification for payment {}: status={}, paid={}, checkoutSessionId={}, paymentIntentId={}",
                paymentReference, result.status(), result.paid(), result.checkoutSessionId(), result.paymentIntentId());

        if (result.paid()) {
            activateSubscription(result.checkoutSessionId(), result.paymentIntentId());
            log.info("Payment {} manually verified and activated via Stripe for user {}", paymentReference, userId);
        } else if ("expired".equalsIgnoreCase(result.status())) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Checkout session expired without payment");
            paymentRepository.save(payment);
            log.info("Payment {} marked as failed due to expired Stripe session for user {}", paymentReference, userId);
            throw new BadRequestException("Payment session has expired. Please try again.");
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Unexpected Stripe session status: " + result.status());
            paymentRepository.save(payment);
            log.warn("Payment {} verification failed with unexpected Stripe session status '{}' for user {}",
                    paymentReference, result.status(), userId);
            throw new BadRequestException("Payment verification failed. Please contact support if the issue persists.");
        }

        return getSubscriptionStatus(userId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal applyDiscount(BigDecimal price, CouponValidationResult coupon) {
        if (coupon.getDiscountType() == DiscountType.PERCENTAGE) {
            BigDecimal factor = BigDecimal.ONE.subtract(
                    coupon.getDiscountValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            return price.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        } else {
            return price.subtract(coupon.getDiscountValue()).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
    }

    private void notifyUser(UUID userId, String planName, Boolean activated, String failReason) {
        userRepository.findById(userId).ifPresent(user -> {
            String name = user.getFirstName() != null ? user.getFirstName() : user.getEmail();
            try {
                if (Boolean.TRUE.equals(activated)) {
                    emailService.sendSubscriptionActivatedEmail(user.getEmail(), name, planName);
                } else if (Boolean.FALSE.equals(activated)) {
                    emailService.sendPaymentFailedEmail(user.getEmail(), name, planName, failReason);
                } else {
                    emailService.sendSubscriptionCancelledEmail(user.getEmail(), name, "your next billing date");
                }
            } catch (Exception ex) {
                log.warn("Failed to send subscription email to {}: {}", user.getEmail(), ex.getMessage());
            }
        });
    }

    private String reasonMessage(String reason) {
        return switch (reason != null ? reason : "") {
            case "expired" -> "Coupon has expired";
            case "already_used" -> "You have already used this coupon";
            case "limit_reached" -> "Coupon usage limit has been reached";
            case "not_found" -> "Coupon not found";
            default -> "Invalid coupon";
        };
    }
}
