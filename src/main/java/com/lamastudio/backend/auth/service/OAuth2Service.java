package com.lamastudio.backend.auth.service;

import com.lamastudio.backend.auth.dto.AuthResponse;
import com.lamastudio.backend.auth.jwt.CookieFactory;
import com.lamastudio.backend.auth.jwt.JwtTokenProvider;
import com.lamastudio.backend.exception.BadRequestException;
import com.lamastudio.backend.role.entity.RoleName;
import com.lamastudio.backend.role.repository.RoleRepository;
import com.lamastudio.backend.user.entity.AccountStatus;
import com.lamastudio.backend.user.entity.AuthProvider;
import com.lamastudio.backend.user.entity.User;
import com.lamastudio.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2Service {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final CookieFactory cookieFactory;

    @Transactional
    public AuthResponse handleOAuthLogin(org.springframework.security.oauth2.core.user.OAuth2User oauthUser, jakarta.servlet.http.HttpServletResponse response) {
        log.info("OAuth2 login initiated for provider: google");
        
        // Extract & validate attributes
        String email = extractEmail(oauthUser);
        String sub = extractSub(oauthUser);
        String firstName = oauthUser.getAttribute("given_name");
        String lastName = oauthUser.getAttribute("family_name");
        String picture = oauthUser.getAttribute("picture");
        
        if (email == null || sub == null) {
            log.warn("Missing required OAuth2 attributes: email={}, sub={}", email, sub);
            throw new BadRequestException("OAuth user missing required attributes (email or sub)");
        }
        
        log.debug("Extracted OAuth2 user attributes: email={}, provider_id={}, firstName={}, lastName={}", 
                  email, sub, firstName, lastName);
        
        // Provision user (create or update)
        User user = provisionUser(email, sub, firstName, lastName, picture);
        log.info("User provisioned successfully: userId={}, email={}, provider={}", 
                 user.getId(), user.getEmail(), user.getProvider());

        // Issue tokens and set cookies
        String accessToken  = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);
        cookieFactory.addAuthCookies(response, accessToken, refreshToken);
        log.debug("JWT tokens issued and auth cookies set for user: {}", user.getEmail());

        return toAuthResponse(user, null);
    }

    /**
     * Provision user by creating new or updating existing.
     * Priority: 1) Lookup by provider+providerId, 2) Lookup by email, 3) Create new
     */
    private User provisionUser(String email, String sub, String firstName, String lastName, String picture) {
        // Priority 1: Lookup by provider + providerId (existing OAuth user)
        Optional<User> byProvider = userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, sub);
        if (byProvider.isPresent()) {
            log.debug("User found by provider+providerId, updating profile: email={}", email);
            return updateOauthUser(byProvider.get(), firstName, lastName, picture);
        }
        
        // Priority 2: Lookup by email (existing LOCAL account, link OAuth provider)
        Optional<User> byEmail = userRepository.findByEmail(email);
        if (byEmail.isPresent()) {
            log.debug("User found by email, linking Google OAuth provider: email={}", email);
            return linkOauthProvider(byEmail.get(), sub, firstName, lastName, picture);
        }
        
        // Priority 3: Create new OAuth user
        log.debug("Creating new OAuth2 user: email={}", email);
        return createOauthUser(email, sub, firstName, lastName, picture);
    }

    private String extractEmail(org.springframework.security.oauth2.core.user.OAuth2User oauthUser) {
        if (oauthUser instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser) {
            return ((org.springframework.security.oauth2.core.oidc.user.OidcUser) oauthUser).getEmail();
        }
        return oauthUser.getAttribute("email");
    }

    private String extractSub(org.springframework.security.oauth2.core.user.OAuth2User oauthUser) {
        if (oauthUser instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser) {
            return ((org.springframework.security.oauth2.core.oidc.user.OidcUser) oauthUser).getSubject();
        }
        return oauthUser.getAttribute("sub");
    }

    private User updateOauthUser(User user, String firstName, String lastName, String picture) {
        log.debug("Updating OAuth2 user profile: email={}", user.getEmail());
        if (firstName != null) {
            user.setFirstName(firstName);
        }
        if (lastName != null) {
            user.setLastName(lastName);
        }
        if (picture != null) {
            user.setAvatarUrl(picture);
        }
        return userRepository.save(user);
    }

    private User linkOauthProvider(User user, String providerId, String firstName, String lastName, String picture) {
        log.debug("Linking Google OAuth provider to existing user: email={}", user.getEmail());
        user.setProvider(AuthProvider.GOOGLE);
        user.setProviderId(providerId);
        if (firstName != null) {
            user.setFirstName(firstName);
        }
        if (lastName != null) {
            user.setLastName(lastName);
        }
        if (picture != null) {
            user.setAvatarUrl(picture);
        }
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private User createOauthUser(String email, String providerId, String firstName, String lastName, String picture) {
        log.debug("Creating new OAuth2 user: email={}", email);
        final User user = new User();
        user.setEmail(email);
        user.setProvider(AuthProvider.GOOGLE);
        user.setProviderId(providerId);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setDisplayName(buildDisplayName(firstName, lastName, email));
        user.setAvatarUrl(picture);
        user.setEmailVerified(true);
        user.setAccountStatus(AccountStatus.ACTIVE);

        Optional<com.lamastudio.backend.role.entity.Role> roleOpt = roleRepository.findByName(RoleName.ROLE_USER);
        if (roleOpt.isPresent()) {
            user.addRole(roleOpt.get());
            log.debug("Assigned ROLE_USER to new OAuth2 user: {}", email);
        }

        User savedUser = userRepository.save(user);
        log.info("New OAuth2 user created: id={}, email={}", savedUser.getId(), savedUser.getEmail());
        return savedUser;
    }

    private String buildDisplayName(String firstName, String lastName, String email) {
        if (firstName != null && lastName != null) return firstName + " " + lastName;
        if (firstName != null) return firstName;
        return email.split("@")[0];
    }

    private AuthResponse toAuthResponse(User user, String message) {
        Set<String> roles = user.getRoles().stream()
                .map(r -> r.getName().name())
                .collect(Collectors.toSet());

        return AuthResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .displayName(user.getDisplayName())
                .avatarUrl(user.getAvatarUrl())
                .roles(roles)
                .provider(user.getProvider().name())
                .emailVerified(user.isEmailVerified())
                .message(message)
                .build();
    }
}
