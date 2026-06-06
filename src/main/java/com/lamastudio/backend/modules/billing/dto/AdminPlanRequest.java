package com.lamastudio.backend.modules.billing.dto;

import com.lamastudio.backend.shared.entity.DomainEnums.BillingCycle;
import com.lamastudio.backend.shared.entity.DomainEnums.VideoQuality;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class AdminPlanRequest {

    @NotBlank(message = "Plan name is required")
    @Size(max = 100)
    private String name;

    private String description;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price must be zero or greater")
    private BigDecimal price;

    @Size(max = 3)
    private String currency;

    private BillingCycle billingCycle;

    @Min(value = 1, message = "maxDevices must be at least 1")
    private Integer maxDevices;

    private VideoQuality videoQuality;

    /** null means unlimited */
    private Integer contentLimit;

    private Boolean isActive = true;
}
