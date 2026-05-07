package com.lamastudio.backend.shared.security.oauth;

import com.lamastudio.backend.modules.auth.service.OAuth2Service;
import com.lamastudio.backend.shared.config.AppProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AppProperties appProperties;
    private final OAuth2Service oAuth2Service;

    @Override
    @Transactional
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = extractEmail(oAuth2User);
        
        log.debug("OAuth2 authentication success for principal: {}", email);

        try {
            log.info("Starting OAuth2 user provisioning for: {}", email);
            
            // Delegate user resolution, token issuance and response cookie handling to OAuth2Service
            oAuth2Service.handleOAuthLogin(oAuth2User, response);

            String returnTo = extractReturnToFromState(request.getParameter("state"));
            UriComponentsBuilder redirectBuilder = UriComponentsBuilder
                .fromUriString(appProperties.getFrontendUrl() + "/callback");
            if (returnTo != null) {
                redirectBuilder.queryParam("returnTo", returnTo);
            }

            String redirectUrl = redirectBuilder.build().toUriString();

            log.info("OAuth2 login completed successfully for: {}", email);
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);
        } catch (Exception ex) {
            log.error("Error handling OAuth2 login for {}: {} | Cause: {}", 
                     email, ex.getMessage(), 
                     ex.getCause() != null ? ex.getCause().getMessage() : "N/A", 
                     ex);
            
            String errorMessage = ex.getMessage() != null ? ex.getMessage() : "OAuth2 processing failed";
            String errorUrl = UriComponentsBuilder
                    .fromUriString(appProperties.getFrontendUrl() + "/auth/error")
                    .queryParam("reason", "oauth_processing_failed")
                    .queryParam("message", URLEncoder.encode(errorMessage, StandardCharsets.UTF_8))
                    .build()
                    .toUriString();
            getRedirectStrategy().sendRedirect(request, response, errorUrl);
        }
    }

    private String extractEmail(OAuth2User user) {
        if (user instanceof OidcUser) {
            return ((OidcUser) user).getEmail();
        }
        return user.getAttribute("email");
    }

    private String extractReturnToFromState(String state) {
        if (state == null || state.isBlank()) return null;
        int idx = state.lastIndexOf("__rt__");
        if (idx < 0) return null;

        String encoded = state.substring(idx + "__rt__".length());
        if (encoded.isBlank()) return null;

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return getSafeReturnTo(decoded);
        } catch (IllegalArgumentException ex) {
            log.warn("Failed to decode returnTo from OAuth state", ex);
            return null;
        }
    }

    private String getSafeReturnTo(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (!trimmed.startsWith("/")) return null;
        if (trimmed.startsWith("//")) return null;
        if (trimmed.matches("^[a-zA-Z]+://.*")) return null;
        return trimmed;
    }
}
