package com.tinniestudio.api.modules.billing.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentSetupIntentResponse {
    private String setupIntentId;
    private String clientSecret;
}
