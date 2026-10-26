package com.tinniestudio.api.billing.service;

import com.tinniestudio.api.modules.billing.dto.*;
import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.billing.repository.SubscriptionPlanRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.billing.service.*;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionService")
class SubscriptionServiceTest {

    @Mock private SubscriptionPlanRepository planRepository;
    @Mock private UserSubscriptionRepository subscriptionRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private com.tinniestudio.api.modules.user.repository.UserProfileRepository userProfileRepository;
    @Mock private CouponService couponService;
    @Mock private StripeService stripeService;

    @InjectMocks private SubscriptionServiceImpl service;

    private UUID userId;
    private SubscriptionPlan silverPlan;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        silverPlan = new SubscriptionPlan();
        silverPlan.setId(UUID.randomUUID());
        silverPlan.setName("SILVER");
        silverPlan.setPrice(new BigDecimal("9.99"));
        silverPlan.setCurrency("CAD");
        silverPlan.setBillingCycle(BillingCycle.MONTHLY);
        silverPlan.setMaxDevices(1);
        silverPlan.setIsActive(true);

    }

    @Nested
    @DisplayName("listPlans()")
    class ListPlans {

        @Test
        @DisplayName("returns only SILVER and GOLD, excludes FREE")
        void returnsOnlySilverAndGold() {
            when(planRepository.findByNameInAndIsActiveTrue(anyList()))
                .thenReturn(List.of(silverPlan));

            List<PlanResponse> plans = service.listPlans();

            assertThat(plans).hasSize(1);
            assertThat(plans.get(0).getName()).isEqualTo("SILVER");
        }
    }

    @Nested
    @DisplayName("validateCoupon()")
    class ValidateCoupon {

        @Test
        @DisplayName("valid coupon returns discounted price")
        void validCoupon_returnsDiscount() {
            when(planRepository.findById(silverPlan.getId())).thenReturn(Optional.of(silverPlan));

            CouponValidationResult result = CouponValidationResult.builder()
                .valid(true).couponId(UUID.randomUUID())
                .discountType(DiscountType.PERCENTAGE).discountValue(new BigDecimal("20"))
                .build();
            when(couponService.validateCoupon("SAVE20", userId)).thenReturn(result);

            CouponValidationResponse response = service.validateCoupon("SAVE20", userId, silverPlan.getId());

            assertThat(response.isValid()).isTrue();
            assertThat(response.getFinalPrice()).isLessThan(new BigDecimal("9.99"));
        }

        @Test
        @DisplayName("expired coupon throws BadRequestException")
        void expiredCoupon_throws() {
            when(planRepository.findById(silverPlan.getId())).thenReturn(Optional.of(silverPlan));
            when(couponService.validateCoupon("EXPIRED", userId)).thenReturn(
                CouponValidationResult.builder().valid(false).reason("expired").build());

            assertThatThrownBy(() -> service.validateCoupon("EXPIRED", userId, silverPlan.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("already_used coupon throws BadRequestException")
        void alreadyUsedCoupon_throws() {
            when(planRepository.findById(silverPlan.getId())).thenReturn(Optional.of(silverPlan));
            when(couponService.validateCoupon("USED", userId)).thenReturn(
                CouponValidationResult.builder().valid(false).reason("already_used").build());

            assertThatThrownBy(() -> service.validateCoupon("USED", userId, silverPlan.getId()))
                .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("limit_reached coupon throws BadRequestException")
        void limitReachedCoupon_throws() {
            when(planRepository.findById(silverPlan.getId())).thenReturn(Optional.of(silverPlan));
            when(couponService.validateCoupon("MAXED", userId)).thenReturn(
                CouponValidationResult.builder().valid(false).reason("limit_reached").build());

            assertThatThrownBy(() -> service.validateCoupon("MAXED", userId, silverPlan.getId()))
                .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("not_found coupon throws BadRequestException")
        void notFoundCoupon_throws() {
            when(planRepository.findById(silverPlan.getId())).thenReturn(Optional.of(silverPlan));
            when(couponService.validateCoupon("GHOST", userId)).thenReturn(
                CouponValidationResult.builder().valid(false).reason("not_found").build());

            assertThatThrownBy(() -> service.validateCoupon("GHOST", userId, silverPlan.getId()))
                .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("confirmPaymentSetup()")
    class ConfirmPaymentSetup {

        @Test
        @DisplayName("first-ever setup starts a 30-day trial")
        void confirmPaymentSetup_firstEverSetup_startsTrial() {
            UUID planId = silverPlan.getId();

            PaymentSetupConfirmRequest request = new PaymentSetupConfirmRequest();
            request.setSetupIntentId("seti_123");
            request.setPlanId(planId);

            when(subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.empty());
            when(stripeService.retrieveSetupIntent("seti_123")).thenReturn(
                new StripeService.VerifySetupIntentResult("seti_123", "cus_123", "pm_123", true));
            when(stripeService.resolveCustomerOwnerUserId("cus_123")).thenReturn(Optional.of(userId.toString()));
            when(planRepository.findById(planId)).thenReturn(Optional.of(silverPlan));
            when(subscriptionRepository.save(any(UserSubscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            service.confirmPaymentSetup(userId, request);

            ArgumentCaptor<UserSubscription> captor = ArgumentCaptor.forClass(UserSubscription.class);
            verify(subscriptionRepository, atLeastOnce()).save(captor.capture());
            UserSubscription saved = captor.getValue();

            assertThat(saved.getStripeCustomerId()).isEqualTo("cus_123");
            assertThat(saved.getStripePaymentMethodId()).isEqualTo("pm_123");
            assertThat(saved.getTrialEndsAt()).isNotNull();
            assertThat(saved.getTrialEndsAt()).isEqualTo(saved.getNextRenewalAt());
            assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(saved.getAutoRenew()).isTrue();
        }

        @Test
        @DisplayName("rejects a SetupIntent whose resolved Stripe Customer belongs to a different user")
        void confirmPaymentSetup_rejectsSetupIntentForDifferentCustomer() {
            PaymentSetupConfirmRequest request = new PaymentSetupConfirmRequest();
            request.setSetupIntentId("seti_123");

            when(stripeService.retrieveSetupIntent("seti_123")).thenReturn(
                new StripeService.VerifySetupIntentResult("seti_123", "cus_someone_else", "pm_123", true));
            when(stripeService.resolveCustomerOwnerUserId("cus_someone_else"))
                .thenReturn(Optional.of(UUID.randomUUID().toString()));

            assertThatThrownBy(() -> service.confirmPaymentSetup(userId, request))
                .isInstanceOf(BadRequestException.class);

            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("fails closed (rejects) when the resolved customer has no owning userId in its metadata")
        void confirmPaymentSetup_rejectsWhenCustomerOwnershipCannotBeResolved() {
            PaymentSetupConfirmRequest request = new PaymentSetupConfirmRequest();
            request.setSetupIntentId("seti_123");

            when(stripeService.retrieveSetupIntent("seti_123")).thenReturn(
                new StripeService.VerifySetupIntentResult("seti_123", "cus_no_metadata", "pm_123", true));
            when(stripeService.resolveCustomerOwnerUserId("cus_no_metadata")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmPaymentSetup(userId, request))
                .isInstanceOf(BadRequestException.class);

            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("first-ever setup rejects a non-purchasable plan (e.g. FREE)")
        void confirmPaymentSetup_rejectsNonPurchasablePlan() {
            SubscriptionPlan freePlan = new SubscriptionPlan();
            freePlan.setId(UUID.randomUUID());
            freePlan.setName("FREE");
            freePlan.setPrice(BigDecimal.ZERO);
            freePlan.setCurrency("CAD");

            PaymentSetupConfirmRequest request = new PaymentSetupConfirmRequest();
            request.setSetupIntentId("seti_123");
            request.setPlanId(freePlan.getId());

            when(subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.empty());
            when(stripeService.retrieveSetupIntent("seti_123")).thenReturn(
                new StripeService.VerifySetupIntentResult("seti_123", "cus_123", "pm_123", true));
            when(stripeService.resolveCustomerOwnerUserId("cus_123")).thenReturn(Optional.of(userId.toString()));
            when(planRepository.findById(freePlan.getId())).thenReturn(Optional.of(freePlan));

            assertThatThrownBy(() -> service.confirmPaymentSetup(userId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not available for purchase");

            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("adding a new card after the paid period already expired triggers a real renewal attempt before access is restored")
        void confirmPaymentSetup_existingSubscriptionPeriodExpired_triggersRenewal() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAutoRenew(false);
            sub.setStripeCustomerId("cus_mine");
            sub.setEndDate(Instant.now().minusSeconds(60));
            sub.setNextRenewalAt(Instant.now().minusSeconds(60));

            PaymentSetupConfirmRequest request = new PaymentSetupConfirmRequest();
            request.setSetupIntentId("seti_123");

            when(subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(Optional.of(sub));
            when(stripeService.retrieveSetupIntent("seti_123")).thenReturn(
                new StripeService.VerifySetupIntentResult("seti_123", "cus_mine", "pm_new", true));
            when(stripeService.resolveCustomerOwnerUserId("cus_mine")).thenReturn(Optional.of(userId.toString()));
            when(subscriptionRepository.findByIdForUpdate(sub.getId())).thenReturn(Optional.of(sub));
            when(stripeService.chargeOffSession(any(), any(), anyLong(), any(), any()))
                .thenReturn(new StripeService.ChargeResult("pi_123", true, null));

            service.confirmPaymentSetup(userId, request);

            assertThat(sub.getAutoRenew()).isTrue();
            verify(stripeService).chargeOffSession(any(), any(), anyLong(), any(), any());
            verify(paymentRepository).save(any());
        }
    }

    @Nested
    @DisplayName("switchPlan()")
    class SwitchPlan {

        @Test
        @DisplayName("rejects switching to a non-purchasable plan")
        void switchPlan_rejectsNonPurchasablePlan() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);

            SubscriptionPlan freePlan = new SubscriptionPlan();
            freePlan.setId(UUID.randomUUID());
            freePlan.setName("FREE");

            when(subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(Optional.of(sub));
            when(planRepository.findById(freePlan.getId())).thenReturn(Optional.of(freePlan));

            assertThatThrownBy(() -> service.switchPlan(userId, freePlan.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not available for purchase");

            verify(subscriptionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("completeSetupFromWebhook()")
    class CompleteSetupFromWebhook {

        @Test
        @DisplayName("applies the new payment method and re-enables a PAST_DUE subscription")
        void appliesNewPaymentMethod_andRetriesWhenPastDue() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.PAST_DUE);
            sub.setAutoRenew(false);
            sub.setStripeCustomerId("cus_123");
            sub.setStripePaymentMethodId("pm_old");
            sub.setNextRenewalAt(Instant.now().minusSeconds(60));

            when(subscriptionRepository.findByStripeCustomerId("cus_123")).thenReturn(Optional.of(sub));
            when(subscriptionRepository.findByIdForUpdate(sub.getId())).thenReturn(Optional.of(sub));
            when(stripeService.chargeOffSession(any(), any(), anyLong(), any(), any()))
                .thenReturn(new StripeService.ChargeResult("pi_123", true, null));

            service.completeSetupFromWebhook("cus_123", "pm_new");

            assertThat(sub.getStripePaymentMethodId()).isEqualTo("pm_new");
            assertThat(sub.getAutoRenew()).isTrue();
            verify(stripeService).chargeOffSession(any(), eq("pm_new"), anyLong(), any(), any());
        }

        @Test
        @DisplayName("no-ops when the payment method already matches what's stored")
        void noOpsWhenAlreadyApplied() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setStripeCustomerId("cus_123");
            sub.setStripePaymentMethodId("pm_123");

            when(subscriptionRepository.findByStripeCustomerId("cus_123")).thenReturn(Optional.of(sub));

            service.completeSetupFromWebhook("cus_123", "pm_123");

            verify(subscriptionRepository, never()).save(any());
            verify(stripeService, never()).chargeOffSession(any(), any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("no-ops without throwing when no subscription exists for that customer")
        void noOpsWhenNoSubscriptionFound() {
            when(subscriptionRepository.findByStripeCustomerId("cus_unknown")).thenReturn(Optional.empty());

            service.completeSetupFromWebhook("cus_unknown", "pm_123");

            verify(subscriptionRepository, never()).save(any());
            verify(stripeService, never()).chargeOffSession(any(), any(), anyLong(), any(), any());
        }
    }

    @Nested
    @DisplayName("getSubscriptionStatus()")
    class GetSubscriptionStatus {

        @Test
        @DisplayName("legacy FREE-plan subscription with no card on file still has access, not NEEDS_SETUP")
        void getSubscriptionStatus_legacyFreePlanNoCard_stillHasAccess() {
            SubscriptionPlan freePlan = new SubscriptionPlan();
            freePlan.setId(UUID.randomUUID());
            freePlan.setName("FREE");

            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(freePlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setContentWatchesUsed(0);
            // Legacy free signup: no Stripe customer, no card ever set up.
            sub.setStripeCustomerId(null);
            sub.setEndDate(null);

            UserProfile profile = new UserProfile();
            profile.setPaymentSetup(false);

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));
            when(userProfileRepository.findById(userId)).thenReturn(Optional.of(profile));
            when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

            SubscriptionStatusResponse result = service.getSubscriptionStatus(userId);

            assertThat(result.isHasAccess()).isTrue();
            assertThat(result.getBlockReason()).isNull();
        }

        @Test
        @DisplayName("grandfathered legacy paid subscriber with no card still has access, not NEEDS_SETUP")
        void getSubscriptionStatus_grandfatheredPaidPlanNoCard_stillHasAccess() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setContentWatchesUsed(0);
            sub.setStripeCustomerId(null);
            sub.setEndDate(null);

            UserProfile profile = new UserProfile();
            profile.setPaymentSetup(false);
            profile.setCardSetupRequired(false);

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));
            when(userProfileRepository.findById(userId)).thenReturn(Optional.of(profile));
            when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

            SubscriptionStatusResponse result = service.getSubscriptionStatus(userId);

            assertThat(result.isHasAccess()).isTrue();
            assertThat(result.getBlockReason()).isNull();
            assertThat(result.isPaymentSetup()).isFalse();
        }

        @Test
        @DisplayName("non-grandfathered paid subscriber with no card is blocked with NEEDS_SETUP")
        void getSubscriptionStatus_newPaidPlanNoCard_blocksWithNeedsSetup() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setContentWatchesUsed(0);
            sub.setStripeCustomerId(null);
            sub.setEndDate(null);

            UserProfile profile = new UserProfile();
            profile.setPaymentSetup(false);
            profile.setCardSetupRequired(true);

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));
            when(userProfileRepository.findById(userId)).thenReturn(Optional.of(profile));
            when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

            SubscriptionStatusResponse result = service.getSubscriptionStatus(userId);

            assertThat(result.isHasAccess()).isFalse();
            assertThat(result.getBlockReason()).isEqualTo("NEEDS_SETUP");
        }

        @Test
        @DisplayName("expired non-grandfathered subscriber with no card is blocked with EXPIRED, not NEEDS_SETUP")
        void getSubscriptionStatus_newPaidPlanNoCardAndExpired_blocksWithExpiredNotNeedsSetup() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setContentWatchesUsed(0);
            sub.setStripeCustomerId(null);
            sub.setEndDate(Instant.now().minusSeconds(60));

            UserProfile profile = new UserProfile();
            profile.setPaymentSetup(false);
            profile.setCardSetupRequired(true);

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));
            when(userProfileRepository.findById(userId)).thenReturn(Optional.of(profile));
            when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

            SubscriptionStatusResponse result = service.getSubscriptionStatus(userId);

            assertThat(result.isHasAccess()).isFalse();
            assertThat(result.getBlockReason()).isEqualTo("EXPIRED");
        }

        @Test
        @DisplayName("blocks access once the paid period has expired, even if paymentSetup is true")
        void getSubscriptionStatus_periodExpired_blocksAccessRegardlessOfPaymentSetup() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAutoRenew(true);
            sub.setStripeCustomerId("cus_mine");
            sub.setEndDate(Instant.now().minusSeconds(60));

            UserProfile profile = new UserProfile();
            profile.setPaymentSetup(true);

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));
            when(userProfileRepository.findById(userId)).thenReturn(Optional.of(profile));
            when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

            SubscriptionStatusResponse result = service.getSubscriptionStatus(userId);

            assertThat(result.isHasAccess()).isFalse();
            assertThat(result.getBlockReason()).isEqualTo("EXPIRED");
        }
    }

    @Nested
    @DisplayName("disableCard()")
    class DisableCard {

        @Test
        @DisplayName("sets autoRenew false; access rides out the remaining paid period")
        void disableCard_setsPaymentSetupAndAutoRenewFalse() {
            UserSubscription sub = new UserSubscription();
            sub.setUserId(userId);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAutoRenew(true);

            when(subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(Optional.of(sub));

            service.disableCard(userId);

            assertThat(sub.getAutoRenew()).isFalse();
            verify(subscriptionRepository).save(sub);
        }

        @Test
        @DisplayName("access still granted via getSubscriptionStatus while the paid period hasn't expired yet")
        void disableCard_periodNotYetExpired_stillHasAccess() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAutoRenew(true);
            sub.setStripeCustomerId("cus_mine");
            sub.setEndDate(Instant.now().plusSeconds(86_400));

            when(subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(Optional.of(sub));

            service.disableCard(userId);

            UserProfile profile = new UserProfile();
            profile.setPaymentSetup(false);
            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));
            when(userProfileRepository.findById(userId)).thenReturn(Optional.of(profile));
            when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

            SubscriptionStatusResponse result = service.getSubscriptionStatus(userId);

            assertThat(result.isHasAccess()).isTrue();
            assertThat(result.getBlockReason()).isNull();
        }
    }

    @Nested
    @DisplayName("processRenewal()")
    class ProcessRenewal {

        @Test
        @DisplayName("success advances endDate/nextRenewalAt by 30 days and writes a RENEWAL payment")
        void processRenewal_success_advancesDatesAndWritesPayment() {
            UUID subscriptionId = UUID.randomUUID();

            UserSubscription sub = new UserSubscription();
            sub.setId(subscriptionId);
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAutoRenew(true);
            sub.setStripeCustomerId("cus_123");
            sub.setStripePaymentMethodId("pm_123");
            Instant dueAt = Instant.now().minusSeconds(60);
            sub.setNextRenewalAt(dueAt);
            sub.setTrialEndsAt(dueAt);

            when(subscriptionRepository.findByIdForUpdate(subscriptionId)).thenReturn(Optional.of(sub));
            when(stripeService.chargeOffSession(eq("cus_123"), eq("pm_123"), eq(999L), eq("CAD"), any()))
                .thenReturn(new StripeService.ChargeResult("pi_123", true, null));

            service.processRenewal(subscriptionId);

            assertThat(sub.getTrialEndsAt()).isNull();
            assertThat(sub.getNextRenewalAt()).isAfter(dueAt);
            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            verify(paymentRepository).save(any());
        }

        @Test
        @DisplayName("success on a lapsed subscription anchors the new period to now, not to the stale nextRenewalAt")
        void processRenewal_successAfterLapse_anchorsNewPeriodToNowNotStaleRenewalDate() {
            UUID subscriptionId = UUID.randomUUID();

            UserSubscription sub = new UserSubscription();
            sub.setId(subscriptionId);
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.PAST_DUE);
            sub.setAutoRenew(true);
            sub.setStripeCustomerId("cus_123");
            sub.setStripePaymentMethodId("pm_123");
            // Subscription lapsed over three weeks ago (e.g. card declined, or the user simply
            // stopped paying) and is only being recharged/retried now.
            Instant staleNextRenewalAt = Instant.now().minus(22, ChronoUnit.DAYS);
            sub.setNextRenewalAt(staleNextRenewalAt);

            when(subscriptionRepository.findByIdForUpdate(subscriptionId)).thenReturn(Optional.of(sub));
            when(stripeService.chargeOffSession(eq("cus_123"), eq("pm_123"), eq(999L), eq("CAD"), any()))
                .thenReturn(new StripeService.ChargeResult("pi_123", true, null));

            Instant before = Instant.now();
            service.processRenewal(subscriptionId);
            Instant after = Instant.now();

            // The new period must be ~30 days from the actual recharge time (now), not 30 days from
            // the stale scheduled date — anchoring to the stale date would land the new
            // nextRenewalAt/endDate only ~8 days out instead of a full 30.
            Instant buggyAnchorResult = staleNextRenewalAt.plus(30, ChronoUnit.DAYS);
            assertThat(Math.abs(java.time.Duration.between(sub.getNextRenewalAt(), buggyAnchorResult).toSeconds()))
                .isGreaterThan(60);
            assertThat(sub.getNextRenewalAt()).isBetween(before.plus(30, ChronoUnit.DAYS), after.plus(30, ChronoUnit.DAYS));
            assertThat(sub.getEndDate()).isBetween(before.plus(30, ChronoUnit.DAYS), after.plus(30, ChronoUnit.DAYS));
            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        }

        @Test
        @DisplayName("failure sets status PAST_DUE and writes no payment")
        void processRenewal_failure_setsPastDue() {
            UUID subscriptionId = UUID.randomUUID();

            UserSubscription sub = new UserSubscription();
            sub.setId(subscriptionId);
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAutoRenew(true);
            sub.setStripeCustomerId("cus_123");
            sub.setStripePaymentMethodId("pm_123");
            sub.setNextRenewalAt(Instant.now().minusSeconds(60));

            when(subscriptionRepository.findByIdForUpdate(subscriptionId)).thenReturn(Optional.of(sub));
            when(stripeService.chargeOffSession(any(), any(), anyLong(), any(), any()))
                .thenReturn(new StripeService.ChargeResult(null, false, "Your card was declined."));

            service.processRenewal(subscriptionId);

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
            verify(paymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("autoRenew turned off under the lock (race with a concurrent disable) does not charge")
        void processRenewal_autoRenewTurnedOffUnderLock_doesNotCharge() {
            UUID subscriptionId = UUID.randomUUID();
            UserSubscription sub = new UserSubscription();
            sub.setId(subscriptionId);
            sub.setAutoRenew(false); // flipped off after the job's initial scan, before the lock

            when(subscriptionRepository.findByIdForUpdate(subscriptionId)).thenReturn(Optional.of(sub));

            service.processRenewal(subscriptionId);

            verify(stripeService, never()).chargeOffSession(any(), any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("nextRenewalAt already advanced into the future (a concurrent call already renewed it) does not charge again")
        void processRenewal_alreadyRenewedConcurrently_doesNotDoubleCharge() {
            UUID subscriptionId = UUID.randomUUID();
            UserSubscription sub = new UserSubscription();
            sub.setId(subscriptionId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAutoRenew(true);
            sub.setStripeCustomerId("cus_123");
            sub.setStripePaymentMethodId("pm_123");
            // A concurrent call already succeeded and pushed this into the future before this call
            // acquired the lock.
            sub.setNextRenewalAt(Instant.now().plusSeconds(3600));

            when(subscriptionRepository.findByIdForUpdate(subscriptionId)).thenReturn(Optional.of(sub));

            service.processRenewal(subscriptionId);

            verify(stripeService, never()).chargeOffSession(any(), any(), anyLong(), any(), any());
            verify(paymentRepository, never()).save(any());
        }
    }
}
