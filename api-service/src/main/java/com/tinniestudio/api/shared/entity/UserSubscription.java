package com.tinniestudio.api.shared.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class UserSubscription extends BaseEntity {

  @Column(nullable = false)
  private UUID userId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "plan_id")
  private SubscriptionPlan plan;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SubscriptionStatus status;

  private Instant startDate;

  private Instant endDate;

  @Column(nullable = false)
  private Boolean autoRenew = true;

  @Column(name = "content_watches_used", nullable = false)
  private int contentWatchesUsed = 0;

  /** Snapshotted from the plan at subscription time. Isolates existing subscribers from plan edits. */
  @Column(name = "max_devices")
  private Integer maxDevices;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Column(name = "stripe_customer_id")
  private String stripeCustomerId;

  @Column(name = "stripe_payment_method_id")
  private String stripePaymentMethodId;

  @Column(name = "trial_ends_at")
  private Instant trialEndsAt;

  @Column(name = "next_renewal_at")
  private Instant nextRenewalAt;

  /** Swapped into {@code plan} by the renewal job on the next successful charge — no proration. */
  @Column(name = "pending_plan_id")
  private UUID pendingPlanId;

  /** Set at payment-setup time if a coupon was applied; consumed (cleared) by the first successful charge only — this is an intro discount, not a recurring one. */
  @Column(name = "pending_coupon_id")
  private UUID pendingCouponId;

  @Column(name = "pending_discount_amount", precision = 10, scale = 2)
  private BigDecimal pendingDiscountAmount;
}
