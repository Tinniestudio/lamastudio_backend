package com.lamastudio.backend.auth.controller;

import com.lamastudio.backend.auth.dto.*;
import com.lamastudio.backend.auth.service.AuthService;
import com.lamastudio.backend.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, login, token refresh, logout, email verification, and password reset. All tokens are set as HTTP-only cookies.")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a new user", description = "Creates a new LOCAL user account. Auto-assigns ROLE_USER, sends verification email, and sets auth cookies.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "User registered successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AuthResponse.class),
                examples = @ExampleObject(name = "success", value = "{\"userId\":\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\",\"email\":\"jane@example.com\",\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"displayName\":\"Jane Doe\",\"avatarUrl\":null,\"roles\":[\"ROLE_USER\"],\"provider\":\"LOCAL\",\"emailVerified\":false,\"message\":\"Registration successful. Please check your email to verify your account.\"}"))),
        @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = "{\"status\":400,\"error\":\"Validation Failed\",\"message\":\"One or more fields failed validation\",\"path\":\"/api/v1/auth/register\",\"timestamp\":\"2024-11-01T12:00:00Z\",\"fieldErrors\":{\"email\":\"Must be a valid email address\",\"password\":\"Password must contain at least one uppercase letter, lowercase letter, digit, and special character\"}}"))),
        @ApiResponse(responseCode = "409", description = "Email already registered",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = "{\"status\":409,\"error\":\"Conflict\",\"message\":\"Email address is already registered\",\"path\":\"/api/v1/auth/register\",\"timestamp\":\"2024-11-01T12:00:00Z\"}")))
    })
    @PostMapping("/register")
    @SecurityRequirements({})
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request, response));
    }

    @Operation(summary = "Login with email and password", description = "Authenticates a LOCAL provider user. Sets access_token (15 min) and refresh_token (7 days) as HTTP-only cookies.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login successful",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AuthResponse.class),
                examples = @ExampleObject(value = "{\"userId\":\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\",\"email\":\"jane@example.com\",\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"displayName\":\"Jane Doe\",\"avatarUrl\":\"https://example.com/avatar.jpg\",\"roles\":[\"ROLE_USER\"],\"provider\":\"LOCAL\",\"emailVerified\":true}"))),
        @ApiResponse(responseCode = "401", description = "Invalid credentials",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Invalid email or password\",\"path\":\"/api/v1/auth/login\",\"timestamp\":\"2024-11-01T12:00:00Z\"}"))),
        @ApiResponse(responseCode = "403", description = "Account suspended or deleted",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/login")
    @SecurityRequirements({})
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return ResponseEntity.ok(authService.login(request, response));
    }

    @Operation(summary = "Refresh access token", description = "Issues a new access_token and refresh_token pair using the refresh_token cookie. Call this when a 401 is received.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tokens refreshed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AuthResponse.class))),
        @ApiResponse(responseCode = "400", description = "Refresh token missing or invalid",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = "{\"status\":400,\"error\":\"Bad Request\",\"message\":\"Refresh token is missing or invalid\",\"path\":\"/api/v1/auth/refresh\",\"timestamp\":\"2024-11-01T12:00:00Z\"}")))
    })
    @SecurityRequirements({})
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        return ResponseEntity.ok(authService.refresh(request, response));
    }

    @Operation(summary = "Logout current user", description = "Clears auth cookies by setting them expired. Stateless — no server session to destroy. Tokens remain cryptographically valid until natural expiry.")
    @ApiResponse(responseCode = "200", description = "Logged out successfully",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            examples = @ExampleObject(value = "{\"message\":\"Logged out successfully\"}")))
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletResponse response) {
        authService.logout(response);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @Operation(summary = "Verify email address", description = "Confirms the user's email using the time-limited token from the registration email. Token expires after 24 hours.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Email verified",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = "{\"message\":\"Email verified successfully\"}"))),
        @ApiResponse(responseCode = "400", description = "Token invalid or expired",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = "{\"status\":400,\"error\":\"Bad Request\",\"message\":\"Email verification token has expired\",\"path\":\"/api/v1/auth/verify-email\",\"timestamp\":\"2024-11-01T12:00:00Z\"}")))
    })
    @SecurityRequirements({})
    @GetMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(
            @Parameter(description = "Verification token from registration email", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(Map.of("message", "Email verified successfully"));
    }

    @Operation(summary = "Request password reset email", description = "Sends a reset link to the email if an active account exists. Always returns 200 to prevent email enumeration. Reset link expires in 1 hour.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Request processed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = "{\"message\":\"If an account with that email exists, a password reset link has been sent\"}"))),
        @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirements({})
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(Map.of("message", "If an account with that email exists, a password reset link has been sent"));
    }

    @Operation(summary = "Reset password using reset token", description = "Sets a new password using the single-use token from the reset email. Token expires after 1 hour.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Password reset successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = "{\"message\":\"Password reset successfully. Please log in.\"}"))),
        @ApiResponse(responseCode = "400", description = "Token invalid/expired or password fails validation",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = "{\"status\":400,\"error\":\"Bad Request\",\"message\":\"Password reset token has expired\",\"path\":\"/api/v1/auth/reset-password\",\"timestamp\":\"2024-11-01T12:00:00Z\"}")))
    })
    @SecurityRequirements({})
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully. Please log in."));
    }
}
