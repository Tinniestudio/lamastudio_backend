package com.tinniestudio.api.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DTO for email verification response.
 * Includes idempotency flag to indicate if email was already verified.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VerifyEmailResponse {

    private String message;

    @Builder.Default
    private boolean alreadyVerified = false;

    private String actionRequired;

    private Instant timestamp;
}
