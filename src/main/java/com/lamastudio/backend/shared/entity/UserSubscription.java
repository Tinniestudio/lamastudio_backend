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
  private SubscriptionStatus status;

  private Instant startDate;

  private Instant endDate;

  private Boolean autoRenew = true;
}
