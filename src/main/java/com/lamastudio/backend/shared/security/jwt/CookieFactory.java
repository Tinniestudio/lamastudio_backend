package com.lamastudio.backend.shared.security.jwt;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.lamastudio.backend.shared.config.AppProperties;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class CookieFactory {

    public static final String ACCESS_TOKEN_COOKIE       = "access_token";
    public static final String REFRESH_TOKEN_COOKIE      = "refresh_token";
    public static final String ADMIN_ACCESS_TOKEN_COOKIE = "admin_access_token";
    public static final String ADMIN_REFRESH_TOKEN_COOKIE = "admin_refresh_token";

    private final AppProperties appProperties;
    private final JwtTokenProvider jwtTokenProvider;
    private final AdminJwtTokenProvider adminJwtTokenProvider;

    public ResponseCookie buildAccessTokenCookie(String token) {
        return buildCookie(
                ACCESS_TOKEN_COOKIE,
                token,
                jwtTokenProvider.getAccessTokenExpiryMs() / 1000
        );
    }

    public ResponseCookie buildRefreshTokenCookie(String token) {
        return buildCookie(
                REFRESH_TOKEN_COOKIE,
                token,
                jwtTokenProvider.getRefreshTokenExpiryMs() / 1000
        );
    }

    public ResponseCookie buildClearAccessTokenCookie() {
        return clearCookie(ACCESS_TOKEN_COOKIE);
    }

    public ResponseCookie buildClearRefreshTokenCookie() {
        return clearCookie(REFRESH_TOKEN_COOKIE);
    }

    public void addAccessTokenCookie(HttpServletResponse response, String accessToken) {
        response.addHeader("Set-Cookie", buildAccessTokenCookie(accessToken).toString());
    }

    public void addAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addHeader("Set-Cookie", buildAccessTokenCookie(accessToken).toString());
        response.addHeader("Set-Cookie", buildRefreshTokenCookie(refreshToken).toString());
    }

    public void clearAuthCookies(HttpServletResponse response) {
        response.addHeader("Set-Cookie", buildClearAccessTokenCookie().toString());
        response.addHeader("Set-Cookie", buildClearRefreshTokenCookie().toString());
    }

    // ── Admin cookies ──────────────────────────────────────────────────────────

    public ResponseCookie buildAdminAccessTokenCookie(String token) {
        return buildCookie(ADMIN_ACCESS_TOKEN_COOKIE, token,
                adminJwtTokenProvider.getAccessTokenExpiryMs() / 1000);
    }

    public ResponseCookie buildAdminRefreshTokenCookie(String token) {
        return buildCookie(ADMIN_REFRESH_TOKEN_COOKIE, token,
                adminJwtTokenProvider.getRefreshTokenExpiryMs() / 1000);
    }

    public void addAdminAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addHeader("Set-Cookie", buildAdminAccessTokenCookie(accessToken).toString());
        response.addHeader("Set-Cookie", buildAdminRefreshTokenCookie(refreshToken).toString());
    }

    public void clearAdminAuthCookies(HttpServletResponse response) {
        response.addHeader("Set-Cookie", clearCookie(ADMIN_ACCESS_TOKEN_COOKIE).toString());
        response.addHeader("Set-Cookie", clearCookie(ADMIN_REFRESH_TOKEN_COOKIE).toString());
    }

    private ResponseCookie buildCookie(String name, String value, long maxAgeSeconds) {
        AppProperties.Cookie cookieConfig = appProperties.getCookie();

        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieConfig.isSecure())
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .sameSite(cookieConfig.getSameSite());

        if (cookieConfig.getDomain() != null && !cookieConfig.getDomain().isBlank()) {
            builder.domain(cookieConfig.getDomain());
        }

        return builder.build();
    }

    private ResponseCookie clearCookie(String name) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/")
                .maxAge(0)
                .sameSite(appProperties.getCookie().getSameSite());

        String domain = appProperties.getCookie().getDomain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }

        return builder.build();
    }
}
