package com.tinniestudio.api.shared.security.jwt;

import com.tinniestudio.api.modules.auth.admin.entity.Admin;
import com.tinniestudio.api.shared.config.AppProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class AdminJwtTokenProvider extends AbstractJwtTokenProvider {

    public static final String AUDIENCE_ADMIN = "admin";
    public static final String CLAIM_SESSION_ID = "sid";

    public AdminJwtTokenProvider(AppProperties appProperties) {
        super(appProperties);
    }

    // ── Access Token ──────────────────────────────────────────────────────────

    public String generateAccessToken(Admin admin, UUID sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + appProperties.getAdminJwt().getAccessToken().getExpirationMs());

        List<String> roles = admin.getRoles().stream()
                .map(Enum::name)
                .collect(Collectors.toList());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(appProperties.getJwt().getIssuer())
                .subject(admin.getId().toString())
                .audience().add(AUDIENCE_ADMIN).and()
                .claim("email", admin.getEmail())
                .claim("roles", roles)
                .claim(CLAIM_SESSION_ID, sessionId != null ? sessionId.toString() : null)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getAccessTokenKey())
                .compact();
    }

    public Claims parseAccessToken(String token) {
        return parseClaims(token, getAccessTokenKey());
    }

    public boolean validateAccessToken(String token) {
        return validateToken(token, getAccessTokenKey(), "admin access");
    }

    // ── Refresh Token ─────────────────────────────────────────────────────────

    public String generateRefreshToken(Admin admin, UUID sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + appProperties.getAdminJwt().getRefreshToken().getExpirationMs());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(appProperties.getJwt().getIssuer())
                .subject(admin.getId().toString())
                .audience().add(AUDIENCE_ADMIN).and()
                .claim(CLAIM_SESSION_ID, sessionId != null ? sessionId.toString() : null)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getRefreshTokenKey())
                .compact();
    }

    public Claims parseRefreshToken(String token) {
        return parseClaims(token, getRefreshTokenKey());
    }

    public boolean validateRefreshToken(String token) {
        return validateToken(token, getRefreshTokenKey(), "admin refresh");
    }

    public long getAccessTokenExpiryMs() {
        return appProperties.getAdminJwt().getAccessToken().getExpirationMs();
    }

    public long getRefreshTokenExpiryMs() {
        return appProperties.getAdminJwt().getRefreshToken().getExpirationMs();
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    private SecretKey getAccessTokenKey() {
        return deriveKey(appProperties.getAdminJwt().getAccessToken().getSecret());
    }

    private SecretKey getRefreshTokenKey() {
        return deriveKey(appProperties.getAdminJwt().getRefreshToken().getSecret());
    }
}
