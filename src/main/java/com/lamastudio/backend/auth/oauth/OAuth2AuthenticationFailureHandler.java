package com.lamastudio.backend.auth.oauth;

import com.lamastudio.backend.config.AppProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Handles OAuth2 authentication failures with graceful error responses and redirects.
 * Logs the error and redirects to the frontend error page with details.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final AppProperties appProperties;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        
        log.error("OAuth2 authentication failed: {}", exception.getMessage(), exception);
        
        String errorMessage = exception.getMessage() != null ? exception.getMessage() : "OAuth2 authentication failed";
        String reason = "oauth_authentication_failed";
        
        // Determine specific error reason from exception type
        if (exception.getCause() != null) {
            String causeMsg = exception.getCause().getMessage();
            if (causeMsg != null && causeMsg.contains("invalid_grant")) {
                reason = "oauth_invalid_grant";
            } else if (causeMsg != null && causeMsg.contains("invalid_client")) {
                reason = "oauth_invalid_client";
            }
        }
        
        // Build error redirect URL with encoded parameters
        String frontendErrorUrl = UriComponentsBuilder
                .fromUriString(appProperties.getFrontendUrl() + "/auth/error")
                .queryParam("reason", reason)
                .queryParam("message", URLEncoder.encode(errorMessage, StandardCharsets.UTF_8))
                .build()
                .toUriString();
        
        log.info("Redirecting to frontend error page: {} with reason: {}", 
                 appProperties.getFrontendUrl() + "/auth/error", reason);
        
        response.sendRedirect(frontendErrorUrl);
    }
}
