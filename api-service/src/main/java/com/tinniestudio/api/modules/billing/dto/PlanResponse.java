package com.tinniestudio.api.modules.billing.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.BillingCycle;
import com.tinniestudio.api.shared.entity.DomainEnums.VideoQuality;
import com.tinniestudio.api.shared.entity.SubscriptionPlan;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
public class PlanResponse {

    private UUID planId;
    private String name;
    private String description;
    private BigDecimal price;
    private String currency;
    private BillingCycle billingCycle;
    private Integer maxDevices;
    private VideoQuality videoQuality;
    private boolean isActive;

    public static PlanResponse from(SubscriptionPlan plan) {
        return PlanResponse.builder()
                .planId(plan.getId())
                .name(plan.getName())
                .description(plan.getDescription())
                .price(plan.getPrice())
                .currency(plan.getCurrency())
                .billingCycle(plan.getBillingCycle())
                .maxDevices(plan.getMaxDevices())
                .videoQuality(plan.getVideoQuality())
                .isActive(Boolean.TRUE.equals(plan.getIsActive()))
                .build();
    }
}
