package com.lamastudio.backend.shared.security.jwt;

import com.lamastudio.backend.shared.config.AppProperties;
import com.lamastudio.backend.shared.entity.User;

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
public class JwtTokenProvider {

    private final AppProperties appProperties;

    public JwtTokenProvider(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    // ── Access Token ──────────────────────────────────────────────────────────

    public String generateAccessToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + appProperties.getJwt().getAccessToken().getExpirationMs());

        List<String> roles = user.getRoles().stream()
                .map(r -> r.getName().name())
                .collect(Collectors.toList());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(appProperties.getJwt().getIssuer())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .claim("provider", user.getProvider().name())
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

    public String generateRefreshToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + appProperties.getJwt().getRefreshToken().getExpirationMs());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(appProperties.getJwt().getIssuer())
                .subject(user.getId().toString())
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

    // ── Shared helpers ────────────────────────────────────────────────────────

    public long getAccessTokenExpiryMs() {
        return appProperties.getJwt().getAccessToken().getExpirationMs();
    }

    public long getRefreshTokenExpiryMs() {
        return appProperties.getJwt().getRefreshToken().getExpirationMs();
    }

    private boolean validateToken(String token, SecretKey key) {
        try {
            parseClaims(token, key);
            return true;
        } catch (ExpiredJwtException ex) {
            log.debug("JWT expired: {}", ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.warn("Unsupported JWT: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.warn("Malformed JWT: {}", ex.getMessage());
        } catch (SecurityException ex) {
            log.warn("Invalid JWT signature: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("JWT claims empty: {}", ex.getMessage());
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
        byte[] keyBytes = Decoders.BASE64.decode(appProperties.getJwt().getAccessToken().getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private SecretKey getRefreshTokenKey() {
        byte[] keyBytes = Decoders.BASE64.decode(appProperties.getJwt().getRefreshToken().getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
