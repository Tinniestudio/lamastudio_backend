package com.tinniestudio.api.auth.jwt;

import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.Role;
import com.tinniestudio.api.shared.entity.RoleName;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.DomainEnums.AuthProvider;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    @Test
    @DisplayName("generateAccessToken embeds claims and validates successfully")
    void generateAndValidateAccessToken() {
        AppProperties props = buildProps(900_000L, 604_800_000L);
        JwtTokenProvider provider = new JwtTokenProvider(props);

        User user = buildUser();

        String token = provider.generateAccessToken(user, UUID.randomUUID());

        assertThat(provider.validateAccessToken(token)).isTrue();
        Claims claims = provider.parseAccessToken(token);
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getIssuer()).isEqualTo(props.getJwt().getIssuer());
        assertThat(claims.get("email", String.class)).isEqualTo(user.getEmail());
        assertThat(claims.get("roles", java.util.List.class)).contains(RoleName.ROLE_USER.name());
        assertThat(claims.get("provider", String.class)).isEqualTo(user.getProvider().name());
    }

    @Test
    @DisplayName("validateAccessToken returns false for expired token")
    void expiredAccessTokenIsInvalid() throws InterruptedException {
        AppProperties props = buildProps(5L, 604_800_000L);
        JwtTokenProvider provider = new JwtTokenProvider(props);
        User user = buildUser();

        String token = provider.generateAccessToken(user, UUID.randomUUID());
        // ensure expiry passes
        Thread.sleep(10L);

        assertThat(provider.validateAccessToken(token)).isFalse();
        assertThatThrownBy(() -> provider.parseAccessToken(token)).isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }

    @Test
    @DisplayName("refresh token round trip validates")
    void refreshTokenValidate() {
        AppProperties props = buildProps(900_000L, 60_000L);
        JwtTokenProvider provider = new JwtTokenProvider(props);
        User user = buildUser();

        String token = provider.generateRefreshToken(user, UUID.randomUUID());

        assertThat(provider.validateRefreshToken(token)).isTrue();
        Claims claims = provider.parseRefreshToken(token);
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getIssuer()).isEqualTo(props.getJwt().getIssuer());
    }

    private User buildUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setProvider(AuthProvider.LOCAL);
        user.setRoles(Set.of(new Role(RoleName.ROLE_USER)));
        return user;
    }

    private AppProperties buildProps(long accessExpiry, long refreshExpiry) {
        AppProperties props = new AppProperties();
        props.setBaseUrl("http://localhost:8080");
        props.setFrontendUrl("http://localhost:3000");

        AppProperties.Jwt.TokenConfig access = new AppProperties.Jwt.TokenConfig();
        access.setSecret("dGhpc2lzbG9uZ2FjY2Vzc3NlY3JldGtleTEyMzQ1Njc4OTA=");
        access.setExpirationMs(accessExpiry);

        AppProperties.Jwt.TokenConfig refresh = new AppProperties.Jwt.TokenConfig();
        refresh.setSecret("dGhpc2lzcmVmcmVzaHNlY3JldGtleTEyMzQ1Njc4OTA=");
        refresh.setExpirationMs(refreshExpiry);

        AppProperties.Jwt jwt = new AppProperties.Jwt();
        jwt.setIssuer("tinniestudio-test");
        jwt.setAccessToken(access);
        jwt.setRefreshToken(refresh);
        props.setJwt(jwt);
        return props;
    }
}
