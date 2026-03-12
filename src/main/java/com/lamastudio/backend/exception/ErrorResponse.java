package com.lamastudio.backend.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ErrorResponse", description = "Standard error envelope returned for all API errors")
public class ErrorResponse {

    @Schema(description = "HTTP status code", example = "400")
    private int status;

    @Schema(description = "Short error classification", example = "Bad Request")
    private String error;

    @Schema(description = "Human-readable error message", example = "Email address is already registered")
    private String message;

    @Schema(description = "Request path that triggered the error", example = "/api/v1/auth/register")
    private String path;

    @Schema(description = "Timestamp when the error occurred (ISO-8601 UTC)", example = "2024-11-01T12:00:00Z")
    private Instant timestamp;

    @Schema(description = "Field-level validation errors (only present on 400 Validation Failed responses)", nullable = true,
        example = "{\"email\":\"Must be a valid email address\",\"password\":\"Password must contain at least one uppercase letter\"}")
    private Map<String, String> fieldErrors;
}
