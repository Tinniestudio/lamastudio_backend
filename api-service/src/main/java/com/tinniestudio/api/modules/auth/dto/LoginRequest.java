package com.tinniestudio.api.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

// @Getter/@Setter only — not @Data: @Data's generated toString() would print the raw
// password if this object is ever logged (e.g. TRACE-level MVC argument-resolution logging).
@Getter
@Setter
@Schema(name = "LoginRequest", description = "Credentials for local email/password authentication")
public class LoginRequest {

    @Schema(description = "Registered email address", example = "jane.doe@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    private String email;

    @Schema(description = "Account password", example = "Str0ng!Pass", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Password is required")
    private String password;
}
