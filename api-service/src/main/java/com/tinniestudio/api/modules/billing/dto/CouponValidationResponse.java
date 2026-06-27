package com.tinniestudio.api.modules.billing.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.DiscountType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
public class CouponValidationResponse {

    private boolean valid;
    private UUID couponId;
    private DiscountType discountType;
    private BigDecimal discountValue;
    private BigDecimal originalPrice;
    private BigDecimal finalPrice;
    private String currency;
}
