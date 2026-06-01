package com.lamastudio.backend.shared.entity;

import java.math.BigDecimal;

import com.lamastudio.backend.shared.entity.DomainEnums.*;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "subscription_plans")
@Getter
@Setter
@NoArgsConstructor
public class SubscriptionPlan extends BaseEntity {

  @Column(nullable = false, length = 100)
  private String name;

  private String description;

  @Column(nullable = false, precision = 10, scale = 2)
  private BigDecimal price;

  @Column(length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  private BillingCycle billingCycle;

  private Integer maxDevices;

  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  private VideoQuality videoQuality;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive = true;

  private Integer contentLimit;
}
