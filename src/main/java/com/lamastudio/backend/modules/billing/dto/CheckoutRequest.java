package com.lamastudio.backend.modules.billing.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class CheckoutRequest {

    @NotNull(message = "planId is required")
    private UUID planId;

    @NotNull(message = "autoRenew is required")
    private Boolean autoRenew;

    private String couponCode;
}
