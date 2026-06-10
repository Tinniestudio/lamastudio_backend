package com.lamastudio.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "ResetPasswordRequest", description = "Token from reset email plus the desired new password")
public class ResetPasswordRequest {

    @Schema(description = "Single-use reset token from the password reset email", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Token is required")
    private String token;

    @Schema(description = "New password (min 8 chars, must include uppercase, lowercase, digit and special character @$!%*?&)", example = "NewStr0ng!Pass", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]+$",
        message = "Password must contain at least one uppercase letter, lowercase letter, digit, and special character"
    )
    private String newPassword;
}
