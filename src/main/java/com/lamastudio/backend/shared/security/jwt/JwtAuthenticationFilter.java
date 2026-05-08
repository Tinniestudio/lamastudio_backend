package com.lamastudio.backend.shared.security.jwt;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.lamastudio.backend.modules.user.service.UserDetailsServiceImpl;

import java.io.IOException;
import java.util.Arrays;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            String token = resolveToken(request);

            if (StringUtils.hasText(token) && jwtTokenProvider.validateAccessToken(token)) {
                Claims claims = jwtTokenProvider.parseAccessToken(token);
                String userId = claims.getSubject();

                UserDetails userDetails = userDetailsService.loadUserById(userId);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ex) {
            log.debug("Could not set user authentication: {}", ex.getMessage());
            // Do not rethrow — let request continue unauthenticated.
            // AuthenticationEntryPoint will handle 401 for protected resources.
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extracts token from HTTP-only cookie first, then falls back to Authorization header
     * (useful for server-to-server or Swagger testing).
     */
    private String resolveToken(HttpServletRequest request) {
        String token = null;

        // 1. Check HTTP-only cookie (preferred)
        if (request.getCookies() != null) {
            token = Arrays.stream(request.getCookies())
                    .filter(c -> ACCESS_TOKEN_COOKIE.equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }

        // 2. Fallback: Authorization header (only if cookie not present)
        if (token == null) {
            String bearerToken = request.getHeader("Authorization");
            if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
                token = bearerToken.substring(BEARER_PREFIX.length());
            }
        }

        return token;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();

    // Support both servletPath and potential inclusion of context-path to avoid accidental filtering in tests
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
