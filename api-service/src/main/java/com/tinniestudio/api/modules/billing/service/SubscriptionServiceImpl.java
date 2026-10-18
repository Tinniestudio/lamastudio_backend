package com.tinniestudio.api.modules.billing.service;

import com.tinniestudio.api.modules.billing.dto.*;
import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.billing.repository.SubscriptionPlanRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
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
import java.util.Optional;
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
    private final com.tinniestudio.api.modules.user.repository.UserProfileRepository userProfileRepository;
    private final CouponService couponService;
    private final StripeService stripeService;

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
    public PaymentSetupIntentResponse startPaymentSetup(UUID userId) {
        String customerId = subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
            .map(UserSubscription::getStripeCustomerId)
            .filter(id -> id != null && !id.isBlank())
            .orElseGet(() -> {
                String email = userRepository.findById(userId)
                    .map(u -> u.getEmail())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                return stripeService.createCustomer(email, Map.of("userId", userId.toString()));
            });

        StripeService.CreateSetupIntentResult result = stripeService.createSetupIntent(customerId);
        return PaymentSetupIntentResponse.builder()
            .setupIntentId(result.setupIntentId())
            .clientSecret(result.clientSecret())
            .build();
    }

    @Override
    @Transactional
    public void confirmPaymentSetup(UUID userId, PaymentSetupConfirmRequest request) {
        StripeService.VerifySetupIntentResult verified =
            stripeService.retrieveSetupIntent(request.getSetupIntentId());
        if (!verified.succeeded()) {
            throw new BadRequestException("Card setup was not completed successfully");
        }

        Optional<UserSubscription> existing = subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId);

        // Cross-user replay guard: the resolved Stripe Customer must belong to this user — either
        // it's brand new (no existing row yet) or it matches the customer already on file.
        existing.ifPresent(sub -> {
            if (sub.getStripeCustomerId() != null && !sub.getStripeCustomerId().equals(verified.customerId())) {
                throw new BadRequestException("Setup intent does not belong to this user");
            }
        });

        if (existing.isEmpty()) {
            // First-ever setup: requires a plan.
            if (request.getPlanId() == null) {
                throw new BadRequestException("planId is required for initial card setup");
            }
            SubscriptionPlan plan = planRepository.findById(request.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));
            assertPlanPurchasable(plan);

            UUID couponId = null;
            BigDecimal discountAmount = null;
            if (request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
                CouponValidationResult coupon = couponService.validateCoupon(request.getCouponCode(), userId);
                if (!coupon.isValid()) {
                    throw new BadRequestException(reasonMessage(coupon.getReason()));
                }
                discountAmount = plan.getPrice().subtract(applyDiscount(plan.getPrice(), coupon));
                couponId = coupon.getCouponId();
            }

            Instant now = Instant.now();
            Instant trialEndsAt = now.plus(30, ChronoUnit.DAYS);

            UserSubscription sub = new UserSubscription();
            sub.setUserId(userId);
            sub.setPlan(plan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAutoRenew(true);
            sub.setStartDate(now);
            sub.setEndDate(trialEndsAt);
            sub.setMaxDevices(plan.getMaxDevices() != null ? plan.getMaxDevices() : 1);
            sub.setStripeCustomerId(verified.customerId());
            sub.setStripePaymentMethodId(verified.paymentMethodId());
            sub.setTrialEndsAt(trialEndsAt);
            sub.setNextRenewalAt(trialEndsAt);
            sub.setPendingCouponId(couponId);
            sub.setPendingDiscountAmount(discountAmount);
            subscriptionRepository.save(sub);

            userProfileRepository.findById(userId).ifPresent(profile -> {
                profile.setPaymentSetup(true);
                userProfileRepository.save(profile);
            });

            log.info("Payment setup completed, trial started: userId={}, trialEndsAt={}", userId, trialEndsAt);
            return;
        }

        // Retry-with-new-card: update the saved method and, if the paid-through period has already
        // elapsed or the last renewal failed, attempt a fresh charge before access is restored —
        // otherwise paymentSetup flipping to true would grant access without ever actually billing
        // the new card (mirrors enableCard's periodExpired check).
        UserSubscription sub = existing.get();
        boolean periodExpired = sub.getEndDate() != null && sub.getEndDate().isBefore(Instant.now());
        sub.setStripeCustomerId(verified.customerId());
        sub.setStripePaymentMethodId(verified.paymentMethodId());
        if (periodExpired || sub.getStatus() == SubscriptionStatus.PAST_DUE) {
            sub.setAutoRenew(true);
        }
        subscriptionRepository.save(sub);

        userProfileRepository.findById(userId).ifPresent(profile -> {
            profile.setPaymentSetup(true);
            userProfileRepository.save(profile);
        });

        if (periodExpired || sub.getStatus() == SubscriptionStatus.PAST_DUE) {
            processRenewal(sub.getId());
        }
    }

    @Override
    @Transactional
    public void disableCard(UUID userId) {
        UserSubscription sub = subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
            .orElseThrow(() -> new ResourceNotFoundException("No subscription found"));
        sub.setAutoRenew(false);
        subscriptionRepository.save(sub);
        userProfileRepository.findById(userId).ifPresent(profile -> {
            profile.setPaymentSetup(false);
            userProfileRepository.save(profile);
        });
        log.info("Card disabled: userId={}, endDate={}", userId, sub.getEndDate());
    }

    @Override
    @Transactional
    public void enableCard(UUID userId) {
        UserSubscription sub = subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
            .orElseThrow(() -> new ResourceNotFoundException("No subscription found"));

        boolean expired = sub.getEndDate() != null && sub.getEndDate().isBefore(Instant.now());
        sub.setAutoRenew(true);
        subscriptionRepository.save(sub);
        userProfileRepository.findById(userId).ifPresent(profile -> {
            profile.setPaymentSetup(true);
            userProfileRepository.save(profile);
        });

        if (expired) {
            processRenewal(sub.getId());
        }
    }

    @Override
    @Transactional
    public void retryPayment(UUID userId) {
        UserSubscription sub = subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
            .orElseThrow(() -> new ResourceNotFoundException("No subscription found"));
        if (sub.getStatus() != SubscriptionStatus.PAST_DUE) {
            throw new BadRequestException("Subscription is not past due");
        }
        processRenewal(sub.getId());
    }

    @Override
    @Transactional
    public void switchPlan(UUID userId, UUID newPlanId) {
        UserSubscription sub = subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
            .orElseThrow(() -> new ResourceNotFoundException("No subscription found"));
        SubscriptionPlan newPlan = planRepository.findById(newPlanId)
            .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));
        assertPlanPurchasable(newPlan);
        sub.setPendingPlanId(newPlanId);
        subscriptionRepository.save(sub);
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
                    .payments(paymentHistory)
                    .paymentSetup(false).hasAccess(false).blockReason("NEEDS_SETUP")
                    .build();
        }

        Instant now = Instant.now();
        boolean periodExpired = sub.getEndDate() != null && sub.getEndDate().isBefore(now);
        String effectiveStatus = periodExpired ? SubscriptionStatus.EXPIRED.name() : sub.getStatus().name();

        boolean paymentSetup = userProfileRepository.findById(userId)
            .map(UserProfile::isPaymentSetup).orElse(false);

        String blockReason;
        boolean hasAccess;
        if (!paymentSetup && sub.getStripeCustomerId() == null) {
            blockReason = "NEEDS_SETUP";
            hasAccess = false;
        } else if (sub.getStatus() == SubscriptionStatus.PAST_DUE) {
            blockReason = "PAST_DUE";
            hasAccess = false;
        } else if (periodExpired) {
            // Elapsed paid-through period blocks access regardless of paymentSetup — otherwise
            // adding a new card (which flips paymentSetup to true) would grant access even if the
            // renewal charge triggered alongside it hasn't actually succeeded yet.
            blockReason = "EXPIRED";
            hasAccess = false;
        } else {
            blockReason = null;
            hasAccess = true;
        }

        SubscriptionPlan subPlan = sub.getPlan();
        String planName = (subPlan != null && !periodExpired) ? subPlan.getName() : "FREE";
        int maxDevices = periodExpired ? 1 : (sub.getMaxDevices() != null ? sub.getMaxDevices() : 1);
        boolean isFreeOrExpired = periodExpired || subPlan == null || "FREE".equalsIgnoreCase(subPlan.getName());
        Integer contentLimit = isFreeOrExpired ? Integer.valueOf(2) : subPlan.getContentLimit();

        return SubscriptionStatusResponse.builder()
                .subscriptionId(sub.getId())
                .plan(planName)
                .status(effectiveStatus)
                .startDate(sub.getStartDate())
                .endDate(sub.getEndDate())
                .autoRenew(Boolean.TRUE.equals(sub.getAutoRenew()))
                .cancelledAt(sub.getCancelledAt())
                .maxDevices(maxDevices)
                .contentWatchesUsed(sub.getContentWatchesUsed())
                .contentWatchesLimit(contentLimit)
                .payments(paymentHistory)
                .paymentSetup(paymentSetup)
                .hasAccess(hasAccess)
                .blockReason(blockReason)
                .trialEndsAt(sub.getTrialEndsAt())
                .nextRenewalAt(sub.getNextRenewalAt())
                .pendingPlanId(sub.getPendingPlanId())
                .build();
    }

    @Override
    @Transactional
    public void processRenewal(UUID subscriptionId) {
        UserSubscription sub = subscriptionRepository.findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription not found"));

        if (!Boolean.TRUE.equals(sub.getAutoRenew())) {
            log.info("Subscription {} no longer set to auto-renew, skipping", subscriptionId);
            return;
        }

        // A concurrent call (e.g. the renewal job overlapping a manual retry, or two overlapping
        // job runs if one exceeds its lock duration) may have already advanced nextRenewalAt into
        // the future by the time this call acquires the row lock above. Re-checking here — rather
        // than trusting the batch SELECT that happened before the lock — prevents a stale duplicate
        // call from charging the card a second time for the same period: the idempotency key is
        // derived from nextRenewalAt, so once it's advanced, a second call would compute a
        // different key and genuinely double-charge instead of being deduplicated by Stripe.
        if (sub.getNextRenewalAt() != null && sub.getNextRenewalAt().isAfter(Instant.now())) {
            log.info("Subscription {} already renewed (nextRenewalAt is in the future) — "
                + "skipping duplicate processRenewal call", subscriptionId);
            return;
        }

        if (sub.getPendingPlanId() != null) {
            SubscriptionPlan newPlan = planRepository.findById(sub.getPendingPlanId()).orElse(null);
            if (newPlan != null) {
                sub.setPlan(newPlan);
            } else {
                log.warn("Subscription {} had pendingPlanId {} that no longer resolves to a plan — "
                    + "switch dropped, staying on the current plan", subscriptionId, sub.getPendingPlanId());
            }
            sub.setPendingPlanId(null);
        }

        SubscriptionPlan plan = sub.getPlan();
        BigDecimal amount = plan.getPrice();
        if (sub.getPendingDiscountAmount() != null) {
            amount = amount.subtract(sub.getPendingDiscountAmount()).max(BigDecimal.ZERO);
        }
        long amountCents = amount.multiply(BigDecimal.valueOf(100)).longValue();
        String idempotencyKey = "renewal-" + subscriptionId + "-" + sub.getNextRenewalAt().getEpochSecond();

        StripeService.ChargeResult result = stripeService.chargeOffSession(
                sub.getStripeCustomerId(), sub.getStripePaymentMethodId(),
                amountCents, plan.getCurrency(), idempotencyKey);

        if (result.succeeded()) {
            Payment payment = new Payment();
            payment.setUserId(sub.getUserId());
            payment.setSubscriptionId(sub.getId());
            payment.setPlanId(plan.getId());
            payment.setType(PaymentType.RENEWAL);
            payment.setProviderReference(result.paymentIntentId());
            payment.setAmount(amount);
            payment.setCurrency(plan.getCurrency());
            payment.setStatus(PaymentStatus.SUCCESSFUL);
            payment.setPaidAt(Instant.now());
            payment.setCouponId(sub.getPendingCouponId());
            payment.setDiscountAmount(sub.getPendingDiscountAmount());
            paymentRepository.save(payment);

            if (sub.getPendingCouponId() != null) {
                try {
                    couponService.redeemCoupon(sub.getPendingCouponId(), sub.getUserId(), sub.getId());
                } catch (Exception ex) {
                    log.warn("Coupon redemption failed for couponId={}: {}", sub.getPendingCouponId(), ex.getMessage());
                }
            }

            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setTrialEndsAt(null);
            sub.setPendingCouponId(null);
            sub.setPendingDiscountAmount(null);
            sub.setEndDate(sub.getNextRenewalAt().plus(30, ChronoUnit.DAYS));
            sub.setNextRenewalAt(sub.getNextRenewalAt().plus(30, ChronoUnit.DAYS));
            subscriptionRepository.save(sub);
            log.info("Renewal succeeded: subscriptionId={}, nextRenewalAt={}", subscriptionId, sub.getNextRenewalAt());
        } else {
            sub.setStatus(SubscriptionStatus.PAST_DUE);
            subscriptionRepository.save(sub);
            log.warn("Renewal failed: subscriptionId={}, reason={}", subscriptionId, result.failureReason());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Only SILVER/GOLD are user-selectable — FREE is system-assigned, and any other plan isn't
     * meant to be chosen directly. Enforced wherever a user supplies a planId (initial card setup,
     * plan switch). */
    private void assertPlanPurchasable(SubscriptionPlan plan) {
        if (!"SILVER".equalsIgnoreCase(plan.getName()) && !"GOLD".equalsIgnoreCase(plan.getName())) {
            throw new BadRequestException("Plan is not available for purchase");
        }
    }

    private BigDecimal applyDiscount(BigDecimal price, CouponValidationResult coupon) {
        if (coupon.getDiscountType() == DiscountType.PERCENTAGE) {
            BigDecimal factor = BigDecimal.ONE.subtract(
                    coupon.getDiscountValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            return price.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        } else {
            return price.subtract(coupon.getDiscountValue()).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
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
