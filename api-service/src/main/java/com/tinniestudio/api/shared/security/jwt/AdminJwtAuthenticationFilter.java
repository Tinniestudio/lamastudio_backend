package com.tinniestudio.api.shared.security.jwt;

import com.tinniestudio.api.modules.auth.admin.service.AdminUserDetailsServiceImpl;
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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Slf4j
@RequiredArgsConstructor
public class AdminJwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ADMIN_ACCESS_TOKEN_COOKIE = "admin_access_token";

    private final AdminJwtTokenProvider adminJwtTokenProvider;
    private final AdminUserDetailsServiceImpl adminUserDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            String token = resolveToken(request);

            if (StringUtils.hasText(token) && adminJwtTokenProvider.validateAccessToken(token)) {
                Claims claims = adminJwtTokenProvider.parseAccessToken(token);
                String adminId = claims.getSubject();

                UserDetails userDetails = adminUserDetailsService.loadUserById(adminId);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities()
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                String sessionId = claims.get(AdminJwtTokenProvider.CLAIM_SESSION_ID, String.class);
                if (sessionId != null) {
                    request.setAttribute("adminSessionId", sessionId);
                }
            }
        } catch (Exception ex) {
            log.debug("Could not set admin authentication: {}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> ADMIN_ACCESS_TOKEN_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/auth/admin/login")
                || path.startsWith("/auth/admin/refresh")
                || path.startsWith("/auth/admin/bootstrap")
                || path.startsWith("/auth/admin/forgot-password")
                || path.startsWith("/auth/admin/reset-password");
    }
}
