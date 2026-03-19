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
        // Extract attributes (support OIDC and standard OAuth2)
        String email = null;
        String sub = null;
        if (oauthUser instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser oidc) {
            email = oidc.getEmail();
            sub = oidc.getSubject();
        } else {
            email = oauthUser.getAttribute("email");
            sub = oauthUser.getAttribute("sub");
        }

        String firstName = oauthUser.getAttribute("given_name");
        String lastName = oauthUser.getAttribute("family_name");
        String picture = oauthUser.getAttribute("picture");

        if (email == null || sub == null) {
            throw new BadRequestException("OAuth user missing required attributes (email or sub)");
        }

        // Resolve user by providerId first, then by email
        User user;
        Optional<User> byProvider = userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, sub);
        if (byProvider.isPresent()) {
            user = updateOauthUser(byProvider.get(), firstName, lastName, picture);
        } else {
            Optional<User> byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                user = linkOauthProvider(byEmail.get(), sub, firstName, lastName, picture);
            } else {
                user = createOauthUser(email, sub, firstName, lastName, picture);
            }
        }

        user.setProvider(AuthProvider.GOOGLE);
        user.setEmailVerified(true);
        user = userRepository.save(user);

        // Issue tokens and set cookies
        String accessToken  = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);
        cookieFactory.addAuthCookies(response, accessToken, refreshToken);

        return toAuthResponse(user, null);
    }

    private User updateOauthUser(User user, String firstName, String lastName, String picture) {
        if (firstName != null) user.setFirstName(firstName);
        if (lastName != null) user.setLastName(lastName);
        if (picture != null) user.setAvatarUrl(picture);
        return userRepository.save(user);
    }

    private User linkOauthProvider(User user, String providerId, String firstName, String lastName, String picture) {
        user.setProvider(AuthProvider.GOOGLE);
        user.setProviderId(providerId);
        if (firstName != null) user.setFirstName(firstName);
        if (lastName != null) user.setLastName(lastName);
        if (picture != null) user.setAvatarUrl(picture);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private User createOauthUser(String email, String providerId, String firstName, String lastName, String picture) {
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
        roleOpt.ifPresent(user::addRole);

        return userRepository.save(user);
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
