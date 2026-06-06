package com.lamastudio.backend.modules.billing.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class SubscriptionStatusResponse {

    private UUID subscriptionId;
    private String plan;
    private String status;
    private Instant startDate;
    private Instant endDate;
    private boolean autoRenew;
    private Instant cancelledAt;
    private int maxDevices;
    private int contentWatchesUsed;
    private Integer contentWatchesLimit;
    private List<PaymentSummary> payments;

    @Getter
    @Builder
    public static class PaymentSummary {
        private UUID paymentId;
        private BigDecimal amount;
        private String currency;
        private String status;
        private Instant paidAt;
    }
}
