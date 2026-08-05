package com.tinniestudio.api.shared.security.jwt;

import com.tinniestudio.api.modules.auth.admin.service.AdminUserDetailsServiceImpl;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BUG 1 regression coverage: an access token that is otherwise valid (correct signature,
 * not expired) must still be rejected once the session it is bound to has been revoked
 * (logout on another device, password change revoking other sessions, admin force-logout, etc).
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String TOKEN = "valid.jwt.token";

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private UserDetailsServiceImpl userDetailsService;
    @Mock private AdminJwtTokenProvider adminJwtTokenProvider;
    @Mock private AdminUserDetailsServiceImpl adminUserDetailsService;
    @Mock private SessionService sessionService;
    @Mock private FilterChain filterChain;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(
                jwtTokenProvider, userDetailsService, adminJwtTokenProvider, adminUserDetailsService, sessionService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("revoked session's access token is rejected even though the JWT itself is valid")
    void revokedSession_rejectsRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();

        Claims claims = mock(Claims.class);
        when(jwtTokenProvider.validateAccessToken(TOKEN)).thenReturn(true);
        when(jwtTokenProvider.parseAccessToken(TOKEN)).thenReturn(claims);
        when(claims.getSubject()).thenReturn(userId.toString());
        when(claims.get(JwtTokenProvider.CLAIM_SESSION_ID, String.class)).thenReturn(sessionId.toString());

        when(sessionService.isSessionActive(userId, sessionId)).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userDetailsService);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("active session's access token authenticates normally")
    void activeSession_authenticates() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();

        Claims claims = mock(Claims.class);
        when(jwtTokenProvider.validateAccessToken(TOKEN)).thenReturn(true);
        when(jwtTokenProvider.parseAccessToken(TOKEN)).thenReturn(claims);
        when(claims.getSubject()).thenReturn(userId.toString());
        when(claims.get(JwtTokenProvider.CLAIM_SESSION_ID, String.class)).thenReturn(sessionId.toString());

        when(sessionService.isSessionActive(userId, sessionId)).thenReturn(true);

        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username(userId.toString())
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        when(userDetailsService.loadUserById(userId.toString())).thenReturn(userDetails);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(userDetails);
        assertThat(request.getAttribute("sessionId")).isEqualTo(sessionId.toString());
        verify(filterChain).doFilter(request, response);
    }
}
