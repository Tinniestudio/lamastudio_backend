package com.lamastudio.backend.auth.oauth;

import com.lamastudio.backend.auth.jwt.CookieFactory;
import com.lamastudio.backend.auth.jwt.JwtTokenProvider;
import com.lamastudio.backend.config.AppProperties;
import com.lamastudio.backend.role.entity.RoleName;
import com.lamastudio.backend.role.repository.RoleRepository;
import com.lamastudio.backend.user.entity.AccountStatus;
import com.lamastudio.backend.user.entity.AuthProvider;
import com.lamastudio.backend.user.entity.User;
import com.lamastudio.backend.user.repository.UserRepository;
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

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final CookieFactory cookieFactory;
    private final AppProperties appProperties;

    @Override
    @Transactional
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        String email = extractEmail(oAuth2User);
        String sub = extractSub(oAuth2User);
        String firstName = oAuth2User.getAttribute("given_name");
        String lastName = oAuth2User.getAttribute("family_name");
        String picture = oAuth2User.getAttribute("picture");

        if (email == null || sub == null) {
            log.error("OAuth2 user missing email or sub. Cannot proceed.");
            getRedirectStrategy().sendRedirect(request, response,
                    appProperties.getFrontendUrl() + "/auth/error?reason=oauth_missing_info");
            return;
        }

        User user = resolveUser(email, sub, firstName, lastName, picture);

        if (!user.isActive()) {
            log.warn("OAuth2 login denied for suspended/deleted user: {}", email);
            getRedirectStrategy().sendRedirect(request, response,
                    appProperties.getFrontendUrl() + "/auth/error?reason=account_inactive");
            return;
        }

        // Issue internal JWTs — never expose Google token to frontend
        String accessToken  = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        cookieFactory.addAuthCookies(response, accessToken, refreshToken);

        String redirectUrl = UriComponentsBuilder
                .fromUriString(appProperties.getFrontendUrl() + "/auth/callback")
                .build().toUriString();

        log.info("OAuth2 login successful for user: {}", email);
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }

    private User resolveUser(String email, String sub, String firstName, String lastName, String picture) {
        // Check by providerId first (handles email change edge case)
        return userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, sub)
                .map(existing -> updateGoogleUser(existing, firstName, lastName, picture))
                .orElseGet(() -> userRepository.findByEmail(email)
                        .map(existing -> linkGoogleProvider(existing, sub, firstName, lastName, picture))
                        .orElseGet(() -> createGoogleUser(email, sub, firstName, lastName, picture)));
    }

    private User updateGoogleUser(User user, String firstName, String lastName, String picture) {
        // Sync profile info on each login
        if (firstName != null) user.setFirstName(firstName);
        if (lastName  != null) user.setLastName(lastName);
        if (picture   != null) user.setAvatarUrl(picture);
        return userRepository.save(user);
    }

    private User linkGoogleProvider(User user, String sub, String firstName, String lastName, String picture) {
        log.info("Linking Google provider to existing account: {}", user.getEmail());
        user.setProvider(AuthProvider.GOOGLE);
        user.setProviderId(sub);
        if (firstName != null) user.setFirstName(firstName);
        if (lastName  != null) user.setLastName(lastName);
        if (picture   != null) user.setAvatarUrl(picture);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private User createGoogleUser(String email, String sub, String firstName, String lastName, String picture) {
        log.info("Creating new user from Google OAuth: {}", email);
        User user = new User();
        user.setEmail(email);
        user.setProvider(AuthProvider.GOOGLE);
        user.setProviderId(sub);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setDisplayName(buildDisplayName(firstName, lastName, email));
        user.setAvatarUrl(picture);
        user.setEmailVerified(true);
        user.setAccountStatus(AccountStatus.ACTIVE);

        roleRepository.findByName(RoleName.ROLE_USER)
                .ifPresent(user::addRole);

        return userRepository.save(user);
    }

    private String buildDisplayName(String firstName, String lastName, String email) {
        if (firstName != null && lastName != null) return firstName + " " + lastName;
        if (firstName != null) return firstName;
        return email.split("@")[0];
    }

    private String extractEmail(OAuth2User user) {
        if (user instanceof OidcUser oidc) return oidc.getEmail();
        return user.getAttribute("email");
    }

    private String extractSub(OAuth2User user) {
        if (user instanceof OidcUser oidc) return oidc.getSubject();
        return user.getAttribute("sub");
    }
}
