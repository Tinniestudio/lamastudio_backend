package com.lamastudio.backend.auth.oauth;

import com.lamastudio.backend.auth.jwt.CookieFactory;
import com.lamastudio.backend.auth.jwt.JwtTokenProvider;
import com.lamastudio.backend.config.AppProperties;
import com.lamastudio.backend.auth.service.OAuth2Service;
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

    try {
    // Delegate user resolution, token issuance and response cookie handling to OAuth2Service
    oAuth2Service.handleOAuthLogin(oAuth2User, response);

        String redirectUrl = UriComponentsBuilder
            .fromUriString(appProperties.getFrontendUrl() + "/auth/callback")
            .build().toUriString();

    log.info("OAuth2 login successful for principal: {}", String.valueOf(oAuth2User.getAttribute("email")));
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    } catch (Exception ex) {
        log.error("Error handling OAuth2 login: {}", ex.getMessage());
        getRedirectStrategy().sendRedirect(request, response,
            appProperties.getFrontendUrl() + "/auth/error?reason=oauth_processing_failed");
    }
    }

    // All provider/user handling moved to OAuth2Service to avoid cycles.
}
