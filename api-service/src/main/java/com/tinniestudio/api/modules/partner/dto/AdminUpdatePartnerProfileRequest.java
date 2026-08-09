package com.tinniestudio.api.modules.partner.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class AdminUpdatePartnerProfileRequest {
    private Boolean isVerified;
    private BigDecimal revenueSharePercentage;
}
