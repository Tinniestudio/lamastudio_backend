package com.lamastudio.backend.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Set;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "AuthResponse", description = "User profile returned after successful authentication. Auth tokens are delivered via HTTP-only cookies, not in this body.")
public class AuthResponse {

    @Schema(description = "Internal user UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private UUID userId;

    @Schema(description = "User's email address", example = "jane.doe@example.com")
    private String email;

    @Schema(description = "First name", example = "Jane")
    private String firstName;

    @Schema(description = "Last name", example = "Doe")
    private String lastName;

    @Schema(description = "Display name shown in UI", example = "Jane Doe")
    private String displayName;

    @Schema(description = "Profile picture URL", example = "https://example.com/avatar.jpg", nullable = true)
    private String avatarUrl;

    @Schema(description = "Granted authority roles", example = "[\"ROLE_USER\"]")
    private Set<String> roles;

    @Schema(description = "Authentication provider", example = "LOCAL", allowableValues = {"LOCAL", "GOOGLE"})
    private String provider;

    @Schema(description = "Whether the user's email has been verified", example = "true")
    private boolean emailVerified;

    @Schema(description = "Optional informational message (e.g. after registration)", example = "Registration successful. Please check your email to verify your account.", nullable = true)
    private String message;
}
