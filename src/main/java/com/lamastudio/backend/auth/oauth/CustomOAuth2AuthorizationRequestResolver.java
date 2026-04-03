package com.lamastudio.backend.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom OAuth2AuthorizationRequestResolver that injects prompt=consent into Google OAuth2 requests.
 * This ensures the user sees the consent screen on every login, preventing cached consent from
 * silently authenticating returning users without confirmation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final ClientRegistrationRepository clientRegistrationRepository;
    private DefaultOAuth2AuthorizationRequestResolver defaultResolver;

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        if (defaultResolver == null) {
            defaultResolver = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/auth/oauth2/authorize");
        }
        OAuth2AuthorizationRequest authRequest = defaultResolver.resolve(request);
        if (authRequest != null) {
            authRequest = enhanceAuthorizationRequest(authRequest, request);
        }
        return authRequest;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        if (defaultResolver == null) {
            defaultResolver = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/auth/oauth2/authorize");
        }
        OAuth2AuthorizationRequest authRequest = defaultResolver.resolve(request, clientRegistrationId);
        if (authRequest != null) {
            authRequest = enhanceAuthorizationRequest(authRequest, request);
        }
        return authRequest;
    }

    /**
     * Add prompt=consent to force re-consent screen on every authorization.
     * This prevents Google from caching consent and silently authenticating returning users.
     */
    private OAuth2AuthorizationRequest enhanceAuthorizationRequest(OAuth2AuthorizationRequest authRequest, HttpServletRequest request) {
        // Only add prompt=consent for Google provider
        String clientId = authRequest.getClientId();
        if (clientId != null && ("google".equalsIgnoreCase(clientId) || clientId.contains("google"))) {
            String safeReturnTo = getSafeReturnTo(request.getParameter("returnTo"));
            String updatedState = authRequest.getState();

            if (safeReturnTo != null) {
                String encodedReturnTo = Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(safeReturnTo.getBytes(StandardCharsets.UTF_8));
                updatedState = (updatedState == null ? "" : updatedState) + "__rt__" + encodedReturnTo;
            }

            Map<String, Object> additionalParams = new LinkedHashMap<>(authRequest.getAdditionalParameters());
            additionalParams.put("prompt", "consent");
            
            log.debug("Injecting prompt=consent into Google OAuth2 authorization request for clientId: {}", clientId);
            
            return OAuth2AuthorizationRequest.from(authRequest)
                    .additionalParameters(additionalParams)
                    .state(updatedState)
                    .build();
        }
        
        log.debug("Not a Google OAuth2 request, skipping prompt=consent injection");
        return authRequest;
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
