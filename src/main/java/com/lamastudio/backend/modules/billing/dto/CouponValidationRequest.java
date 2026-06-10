package com.lamastudio.backend.modules.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class CouponValidationRequest {

    @NotBlank(message = "code is required")
    private String code;

    @NotNull(message = "planId is required")
    private UUID planId;
}
