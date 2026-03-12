package com.lamastudio.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(name = "ForgotPasswordRequest", description = "Email address to send password reset link to")
public class ForgotPasswordRequest {

    @Schema(description = "Registered email address", example = "jane.doe@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    private String email;
}
