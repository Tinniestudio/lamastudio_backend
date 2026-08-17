package com.tinniestudio.api.modules.content.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Mirrors admin.dto.RejectApplicationRequest's shape for the same action on Content. */
@Getter @Setter @NoArgsConstructor
public class RejectContentRequest {
    @NotBlank
    private String reason;
}
