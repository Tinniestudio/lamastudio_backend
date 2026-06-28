package com.lamastudio.backend.billing.service;

import com.lamastudio.backend.modules.auth.service.EmailService;
import com.lamastudio.backend.modules.billing.dto.*;
import com.lamastudio.backend.modules.billing.repository.PaymentRepository;
import com.lamastudio.backend.modules.billing.repository.SubscriptionPlanRepository;
import com.lamastudio.backend.modules.billing.repository.UserSubscriptionRepository;
import com.lamastudio.backend.modules.billing.service.*;
import com.lamastudio.backend.modules.user.repository.UserRepository;
import com.lamastudio.backend.shared.config.AppProperties;
import com.lamastudio.backend.shared.entity.*;
import com.lamastudio.backend.shared.entity.DomainEnums.*;
import com.lamastudio.backend.shared.exception.BadRequestException;
import com.lamastudio.backend.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
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
    @Mock private CouponService couponService;
    @Mock private StripeService stripeService;
    @Mock private EmailService emailService;
    @Mock private AppProperties appProperties;

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
    @DisplayName("initiateCheckout()")
    class InitiateCheckout {

        @Test
        @DisplayName("creates PENDING payment and returns Stripe checkout URL")
        void createsPaymentAndReturnsUrl() {
            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
            when(planRepository.findById(silverPlan.getId())).thenReturn(Optional.of(silverPlan));
            when(appProperties.getFrontendUrl()).thenReturn("https://frontend.example.com");
            when(stripeService.createCheckoutSession(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new StripeService.CreateCheckoutResult("cs_123", "pi_123", "https://checkout.stripe.com/pay/cs_123"));

            Payment savedPayment = new Payment();
            savedPayment.setId(UUID.randomUUID());
            savedPayment.setUserId(userId);
            savedPayment.setPlanId(silverPlan.getId());
            savedPayment.setAmount(new BigDecimal("9.99"));
            savedPayment.setCurrency("CAD");
            savedPayment.setAutoRenew(true);
            savedPayment.setStatus(PaymentStatus.PENDING);
            when(paymentRepository.save(any())).thenReturn(savedPayment);

            CheckoutRequest request = new CheckoutRequest();
            request.setPlanId(silverPlan.getId());
            request.setAutoRenew(true);

            CheckoutResponse response = service.initiateCheckout(userId, request);

            assertThat(response.getPaymentUrl()).isEqualTo("https://checkout.stripe.com/pay/cs_123");
            assertThat(response.getPlanName()).isEqualTo("SILVER");
            verify(paymentRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("user with existing active paid subscription throws BadRequestException")
        void activeSubscriptionExists_throws() {
            UserSubscription existing = new UserSubscription();
            existing.setPlan(silverPlan);
            existing.setStatus(SubscriptionStatus.ACTIVE);

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(existing));

            CheckoutRequest request = new CheckoutRequest();
            request.setPlanId(silverPlan.getId());
            request.setAutoRenew(true);

            assertThatThrownBy(() -> service.initiateCheckout(userId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already have an active subscription");
        }
    }

    @Nested
    @DisplayName("activateSubscription()")
    class ActivateSubscription {

        @Test
        @DisplayName("creates ACTIVE subscription and marks payment SUCCESSFUL")
        void activatesSubscription() {
            Payment payment = new Payment();
            payment.setId(UUID.randomUUID());
            payment.setUserId(userId);
            payment.setPlanId(silverPlan.getId());
            payment.setAmount(new BigDecimal("9.99"));
            payment.setCurrency("CAD");
            payment.setAutoRenew(true);
            payment.setStatus(PaymentStatus.PENDING);

            when(paymentRepository.existsByProviderReferenceAndStatus("pi_123", PaymentStatus.SUCCESSFUL))
                .thenReturn(false);
            when(paymentRepository.findByProviderReference("pi_123")).thenReturn(Optional.of(payment));
            when(planRepository.findById(silverPlan.getId())).thenReturn(Optional.of(silverPlan));
            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());

            UserSubscription saved = new UserSubscription();
            saved.setId(UUID.randomUUID());
            when(subscriptionRepository.save(any())).thenReturn(saved);
            when(paymentRepository.save(any())).thenReturn(payment);
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            service.activateSubscription("cs_123", "pi_123");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESSFUL);
            assertThat(payment.getPaidAt()).isNotNull();
            verify(subscriptionRepository).save(any());
        }

        @Test
        @DisplayName("second webhook for same providerReference is idempotent — no duplicate subscription")
        void duplicateWebhook_isIdempotent() {
            when(paymentRepository.existsByProviderReferenceAndStatus("pi_123", PaymentStatus.SUCCESSFUL))
                .thenReturn(true);

            service.activateSubscription("cs_123", "pi_123");

            verify(subscriptionRepository, never()).save(any());
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
    @DisplayName("cancelSubscription()")
    class CancelSubscription {

        @Test
        @DisplayName("active paid subscription sets autoRenew=false and cancelledAt")
        void cancelsPaidSubscription() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setEndDate(Instant.now().plusSeconds(86400));

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenReturn(sub);
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            service.cancelSubscription(userId);

            assertThat(sub.getCancelledAt()).isNotNull();
            assertThat(sub.getAutoRenew()).isFalse();
            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        }

        @Test
        @DisplayName("no active subscription throws ResourceNotFoundException")
        void noSubscription_throws() {
            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelSubscription(userId))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("already cancelled subscription throws BadRequestException")
        void alreadyCancelled_throws() {
            UserSubscription sub = new UserSubscription();
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setCancelledAt(Instant.now().minusSeconds(100));

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));

            assertThatThrownBy(() -> service.cancelSubscription(userId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already cancelled");
        }
    }
}
