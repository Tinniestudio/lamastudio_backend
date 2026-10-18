package com.tinniestudio.api.modules.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class PaymentSetupConfirmRequest {

    @NotBlank(message = "setupIntentId is required")
    private String setupIntentId;

    /** Required only on first-ever setup (no existing UserSubscription row); ignored otherwise. */
    private UUID planId;

    /** Optional, first-ever setup only — applied once, to the trial-end charge. */
    private String couponCode;
}
