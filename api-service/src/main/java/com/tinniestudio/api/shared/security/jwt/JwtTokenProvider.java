package com.tinniestudio.api.shared.security.jwt;

import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JwtTokenProvider extends AbstractJwtTokenProvider {

    public static final String AUDIENCE_USER = "user";
    public static final String CLAIM_SESSION_ID = "sid";
    public static final String CLAIM_PURPOSE = "purpose";
    public static final String PURPOSE_DEVICE_LIMIT_RESOLUTION = "device_limit_resolution";
    private static final long DEVICE_LIMIT_RESOLUTION_EXPIRY_MS = 5 * 60 * 1000L;

    public JwtTokenProvider(AppProperties appProperties) {
        super(appProperties);
    }

    // ── Access Token ──────────────────────────────────────────────────────────

    public String generateAccessToken(User user, UUID sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + appProperties.getJwt().getAccessToken().getExpirationMs());

        List<String> roles = user.getRoles().stream()
                .map(r -> r.getName().name())
                .collect(Collectors.toList());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(appProperties.getJwt().getIssuer())
                .subject(user.getId().toString())
                .audience().add(AUDIENCE_USER).and()
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .claim("provider", user.getProvider().name())
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
        return validateToken(token, getAccessTokenKey(), "user access");
    }

    // ── Refresh Token ─────────────────────────────────────────────────────────

    public String generateRefreshToken(User user, UUID sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + appProperties.getJwt().getRefreshToken().getExpirationMs());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(appProperties.getJwt().getIssuer())
                .subject(user.getId().toString())
                .audience().add(AUDIENCE_USER).and()
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
        return validateToken(token, getRefreshTokenKey(), "user refresh");
    }

    // ── Device Limit Resolution Token ────────────────────────────────────────
    // Single-use, 5-minute-lived token handed to the client in a 409 DEVICE_LIMIT_REACHED
    // response. Signed with the access-token key (no new secret needed) but carries a "purpose"
    // claim so it can never be mistaken for — or substituted with — a real access token: an
    // attacker who captured one couldn't use it to authenticate, only to complete the one
    // specific "pick a device to sign out" action it was issued for.

    public String generateDeviceLimitResolutionToken(UUID userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + DEVICE_LIMIT_RESOLUTION_EXPIRY_MS);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(appProperties.getJwt().getIssuer())
                .subject(userId.toString())
                .audience().add(AUDIENCE_USER).and()
                .claim(CLAIM_PURPOSE, PURPOSE_DEVICE_LIMIT_RESOLUTION)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getAccessTokenKey())
                .compact();
    }

    public Claims parseDeviceLimitResolutionToken(String token) {
        return parseClaims(token, getAccessTokenKey());
    }

    public boolean validateDeviceLimitResolutionToken(String token) {
        if (!validateToken(token, getAccessTokenKey(), "device limit resolution")) {
            return false;
        }
        try {
            Claims claims = parseDeviceLimitResolutionToken(token);
            return PURPOSE_DEVICE_LIMIT_RESOLUTION.equals(claims.get(CLAIM_PURPOSE, String.class));
        } catch (Exception ex) {
            return false;
        }
    }

    // ── Expiry helpers ────────────────────────────────────────────────────────

    public long getAccessTokenExpiryMs() {
        return appProperties.getJwt().getAccessToken().getExpirationMs();
    }

    public long getRefreshTokenExpiryMs() {
        return appProperties.getJwt().getRefreshToken().getExpirationMs();
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    private SecretKey getAccessTokenKey() {
        return deriveKey(appProperties.getJwt().getAccessToken().getSecret());
    }

    private SecretKey getRefreshTokenKey() {
        return deriveKey(appProperties.getJwt().getRefreshToken().getSecret());
    }
}
