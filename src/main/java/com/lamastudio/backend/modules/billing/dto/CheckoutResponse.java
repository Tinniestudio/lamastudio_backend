package com.lamastudio.backend.modules.billing.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
public class CheckoutResponse {

    private UUID paymentId;
    private String paymentReference;
    private String paymentUrl;
    private BigDecimal amount;
    private String currency;
    private String planName;
    private boolean autoRenew;
}
