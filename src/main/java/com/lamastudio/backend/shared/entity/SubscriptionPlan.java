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

  private String name;

  private String description;

  @Column(nullable = false)
  private BigDecimal price;

  private String currency;

  @Enumerated(EnumType.STRING)
  private BillingCycle billingCycle;

  private Integer maxDevices;

  @Enumerated(EnumType.STRING)
  private VideoQuality videoQuality;

  private Boolean isActive = true;

  private Integer contentLimit;
}
