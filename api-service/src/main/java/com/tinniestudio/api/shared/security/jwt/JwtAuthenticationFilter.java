package com.tinniestudio.api.shared.security.jwt;

import com.tinniestudio.api.modules.auth.admin.service.AdminUserDetailsServiceImpl;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String ADMIN_ACCESS_TOKEN_COOKIE = "admin_access_token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsServiceImpl userDetailsService;
    private final AdminJwtTokenProvider adminJwtTokenProvider;
    private final AdminUserDetailsServiceImpl adminUserDetailsService;
    private final SessionService sessionService;

    // @Lazy on UserDetailsServiceImpl/AdminUserDetailsServiceImpl breaks the circular startup
    // dependency: SecurityConfig → JwtAuthenticationFilter → *DetailsServiceImpl → *Repository.
    // The proxy is injected immediately; the real bean is created on first HTTP request.
    //
    // This filter recognizes BOTH the user (access_token) and admin (admin_access_token)
    // cookies/claims — several /admin/** business endpoints (partner applications, users,
    // audit log, content moderation) are ADMIN-only or ADMIN-or-PARTNER, and all of them sit
    // behind this chain (chain @Order(1) covers only /auth/admin/** login/refresh/etc, not
    // these business paths). Without this, a real admin login (which sets admin_access_token,
    // signed with a different secret than the user token) could never authenticate against any
    // /admin/** business endpoint — invisible to tests that bypass real JWT resolution via
    // @WithMockUser.
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    @Lazy UserDetailsServiceImpl userDetailsService,
                                    AdminJwtTokenProvider adminJwtTokenProvider,
                                    @Lazy AdminUserDetailsServiceImpl adminUserDetailsService,
                                    @Lazy SessionService sessionService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
        this.adminJwtTokenProvider = adminJwtTokenProvider;
        this.adminUserDetailsService = adminUserDetailsService;
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            String userToken = resolveCookie(request, ACCESS_TOKEN_COOKIE);
            String bearerToken = resolveBearer(request);

            if (StringUtils.hasText(userToken) && jwtTokenProvider.validateAccessToken(userToken)) {
                authenticateAsUser(request, userToken);
            } else if (StringUtils.hasText(bearerToken) && jwtTokenProvider.validateAccessToken(bearerToken)) {
                authenticateAsUser(request, bearerToken);
            } else {
                String adminToken = resolveCookie(request, ADMIN_ACCESS_TOKEN_COOKIE);
                if (StringUtils.hasText(adminToken) && adminJwtTokenProvider.validateAccessToken(adminToken)) {
                    authenticateAsAdmin(request, adminToken);
                }
            }
        } catch (Exception ex) {
            log.debug("Could not set authentication: {}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private void authenticateAsUser(HttpServletRequest request, String token) {
        Claims claims = jwtTokenProvider.parseAccessToken(token);
        String userId = claims.getSubject();
        String sessionId = claims.get(JwtTokenProvider.CLAIM_SESSION_ID, String.class);

        // BUG FIX: the JWT signature being valid only proves the token was issued by us — it
        // says nothing about whether the session it was minted for has since been revoked
        // (logout on another device, password change, admin force-logout/suspend/ban). Without
        // this check a stolen or "logged out" access token stays usable for its full remaining
        // lifetime. Reject (fall through unauthenticated) rather than throw, consistent with the
        // rest of this filter's fail-closed-but-quiet posture.
        if (sessionId != null && !sessionService.isSessionActive(UUID.fromString(userId), UUID.fromString(sessionId))) {
            log.debug("Rejecting access token for user {}: session {} is revoked or not found", userId, sessionId);
            return;
        }

        UserDetails userDetails = userDetailsService.loadUserById(userId);
        setAuthentication(request, userDetails);

        if (sessionId != null) {
            request.setAttribute("sessionId", sessionId);
        }
    }

    private void authenticateAsAdmin(HttpServletRequest request, String token) {
        Claims claims = adminJwtTokenProvider.parseAccessToken(token);
        String adminId = claims.getSubject();

        UserDetails adminDetails = adminUserDetailsService.loadUserById(adminId);
        setAuthentication(request, adminDetails);

        String sessionId = claims.get(AdminJwtTokenProvider.CLAIM_SESSION_ID, String.class);
        if (sessionId != null) {
            request.setAttribute("adminSessionId", sessionId);
        }
    }

    private void setAuthentication(HttpServletRequest request, UserDetails userDetails) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities()
                );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String resolveCookie(HttpServletRequest request, String cookieName) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> cookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private String resolveBearer(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/auth/register")
            || path.startsWith("/auth/login")
            || path.startsWith("/auth/refresh")
            || path.startsWith("/auth/oauth2")
            || path.startsWith("/login/oauth2")
            || path.startsWith("/api/v1/auth/register")
            || path.startsWith("/api/v1/auth/login")
            || path.startsWith("/api/v1/auth/refresh")
            || path.startsWith("/api/v1/auth/oauth2")
            || path.startsWith("/api/v1/login/oauth2");
    }
}
