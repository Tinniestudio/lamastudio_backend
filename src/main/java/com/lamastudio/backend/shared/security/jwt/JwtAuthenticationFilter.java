package com.lamastudio.backend.shared.security.jwt;

import com.lamastudio.backend.modules.user.service.UserDetailsServiceImpl;
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

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsServiceImpl userDetailsService;

    // @Lazy on UserDetailsServiceImpl breaks the circular startup dependency:
    // SecurityConfig → JwtAuthenticationFilter → UserDetailsServiceImpl → UserRepository
    // The proxy is injected immediately; the real bean is created on first HTTP request.
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    @Lazy UserDetailsServiceImpl userDetailsService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
    }

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
                                userDetails, null, userDetails.getAuthorities()
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                String sessionId = claims.get(JwtTokenProvider.CLAIM_SESSION_ID, String.class);
                if (sessionId != null) {
                    request.setAttribute("sessionId", sessionId);
                }
            }
        } catch (Exception ex) {
            log.debug("Could not set user authentication: {}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            String cookie = Arrays.stream(request.getCookies())
                    .filter(c -> ACCESS_TOKEN_COOKIE.equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
            if (cookie != null) return cookie;
        }

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
