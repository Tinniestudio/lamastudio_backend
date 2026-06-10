package com.lamastudio.backend.modules.billing.dto;

import com.lamastudio.backend.shared.entity.DomainEnums.DiscountType;
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
