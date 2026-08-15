package com.tinniestudio.api.shared.security.jwt;

import com.tinniestudio.api.shared.config.AppProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
// NOT io.jsonwebtoken.SecurityException (that resolves to java.lang.SecurityException with no
// explicit import, since it isn't covered by the io.jsonwebtoken.* wildcard the original two
// copies of this code used — a real latent bug found while consolidating: a signature failure
// (io.jsonwebtoken.security.SignatureException, which extends this) would propagate uncaught out
// of validateToken() instead of returning false, in both the old JwtTokenProvider and
// AdminJwtTokenProvider.
import io.jsonwebtoken.security.SecurityException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;

/**
 * Shared JWT signing/parsing/validation for {@link JwtTokenProvider} (user tokens) and
 * {@link AdminJwtTokenProvider} (admin tokens). Token generation stays in the two subclasses —
 * the claims each issues genuinely differ (Admin.roles is a plain enum set, User.roles is a
 * Role-entity relation; the user token also carries a "provider" claim admin tokens don't) — but
 * key derivation, signature parsing, and exception-to-boolean validation logic were previously
 * copy-pasted verbatim in both classes, which is exactly the kind of security-sensitive code
 * where a fix applied to one and forgotten in the other is easy to miss.
 */
@Slf4j
abstract class AbstractJwtTokenProvider {

    protected final AppProperties appProperties;

    protected AbstractJwtTokenProvider(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    protected SecretKey deriveKey(String base64Secret) {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
    }

    protected Claims parseClaims(String token, SecretKey key) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * @param tokenLabel short description used only in log messages (e.g. "user access",
     *                   "admin refresh") so failures remain distinguishable in shared logs.
     */
    protected boolean validateToken(String token, SecretKey key, String tokenLabel) {
        try {
            parseClaims(token, key);
            return true;
        } catch (ExpiredJwtException ex) {
            log.debug("{} JWT expired: {}", tokenLabel, ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.warn("Unsupported {} JWT: {}", tokenLabel, ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.warn("Malformed {} JWT: {}", tokenLabel, ex.getMessage());
        } catch (SecurityException ex) {
            log.warn("Invalid {} JWT signature: {}", tokenLabel, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("{} JWT claims empty: {}", tokenLabel, ex.getMessage());
        }
        return false;
    }
}
