package com.lamastudio.backend.shared.entity;

import java.time.Instant;
import java.util.UUID;

import com.lamastudio.backend.shared.entity.DomainEnums.SubscriptionStatus;

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

  @Column(name = "cancelled_at")
  private Instant cancelledAt;
}
