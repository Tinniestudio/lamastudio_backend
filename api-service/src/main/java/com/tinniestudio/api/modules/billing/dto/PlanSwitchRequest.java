package com.tinniestudio.api.modules.billing.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class PlanSwitchRequest {

    @NotNull(message = "planId is required")
    private UUID planId;
}
