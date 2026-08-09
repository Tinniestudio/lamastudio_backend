package com.tinniestudio.api.modules.appeal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class SubmitAppealRequest {
    @NotBlank
    @Size(max = 4000)
    private String reason;
}
