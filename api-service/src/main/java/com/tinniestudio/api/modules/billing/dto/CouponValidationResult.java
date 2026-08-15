package com.tinniestudio.api.modules.billing.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.DiscountType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
public class CouponValidationResult {
    private boolean valid;
    private String reason;
    private UUID couponId;
    private DiscountType discountType;
    private BigDecimal discountValue;
    private String currency;
}
