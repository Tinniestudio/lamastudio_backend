package com.tinniestudio.api.shared.security.jwt;

import com.tinniestudio.api.modules.auth.admin.entity.Admin;
import com.tinniestudio.api.shared.config.AppProperties;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AdminJwtTokenProvider {

    public static final String AUDIENCE_ADMIN = "admin";
    public static final String CLAIM_SESSION_ID = "sid";

    private final AppProperties appProperties;

    public AdminJwtTokenProvider(AppProperties appProperties) {
        this.appProperties = appProperties;
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
        return validateToken(token, getAccessTokenKey());
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
        return validateToken(token, getRefreshTokenKey());
    }

    public long getAccessTokenExpiryMs() {
        return appProperties.getAdminJwt().getAccessToken().getExpirationMs();
    }

    public long getRefreshTokenExpiryMs() {
        return appProperties.getAdminJwt().getRefreshToken().getExpirationMs();
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private boolean validateToken(String token, SecretKey key) {
        try {
            parseClaims(token, key);
            return true;
        } catch (ExpiredJwtException ex) {
            log.debug("Admin JWT expired: {}", ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.warn("Unsupported admin JWT: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.warn("Malformed admin JWT: {}", ex.getMessage());
        } catch (SecurityException ex) {
            log.warn("Invalid admin JWT signature: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("Admin JWT claims empty: {}", ex.getMessage());
        }
        return false;
    }

    private Claims parseClaims(String token, SecretKey key) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getAccessTokenKey() {
        byte[] keyBytes = Decoders.BASE64.decode(appProperties.getAdminJwt().getAccessToken().getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private SecretKey getRefreshTokenKey() {
        byte[] keyBytes = Decoders.BASE64.decode(appProperties.getAdminJwt().getRefreshToken().getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
