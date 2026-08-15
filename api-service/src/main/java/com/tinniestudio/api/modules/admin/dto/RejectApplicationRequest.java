package com.tinniestudio.api.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class RejectApplicationRequest {
    @NotBlank
    private String reason;
}
