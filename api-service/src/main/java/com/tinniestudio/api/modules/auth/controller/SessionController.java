package com.tinniestudio.api.modules.auth.controller;

import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.shared.exception.ErrorResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth/sessions")
@RequiredArgsConstructor
@Tag(name = "Sessions", description = "Self-service device/session management for the authenticated user.")
public class SessionController {

    private final SessionService sessionService;

    @Operation(summary = "Revoke one of your own sessions", description = "Signs out a specific device. Already-revoked sessions are a no-op; your own current session cannot be revoked this way — use logout instead.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Device signed out (or already was — idempotent)"),
        @ApiResponse(responseCode = "400", description = "sessionId refers to the caller's own current session — use logout instead",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "sessionId does not belong to the authenticated user",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{sessionId}/revoke")
    public ResponseEntity<Map<String, String>> revoke(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID sessionId,
            HttpServletRequest request) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        String sessionIdAttr = (String) request.getAttribute("sessionId");
        UUID currentSessionId = sessionIdAttr != null ? UUID.fromString(sessionIdAttr) : null;

        sessionService.selfRevokeSession(userId, sessionId, currentSessionId);
        return ResponseEntity.ok(Map.of("message", "Device signed out"));
    }
}
