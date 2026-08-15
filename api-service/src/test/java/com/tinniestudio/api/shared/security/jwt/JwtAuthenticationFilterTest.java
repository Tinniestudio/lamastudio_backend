package com.tinniestudio.api.shared.security.jwt;

import com.tinniestudio.api.modules.auth.admin.service.AdminUserDetailsServiceImpl;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.exception.AccountNotActiveException;
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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers two independent JwtAuthenticationFilter behaviors:
 * <p>
 * 1. Session-scoped revocation: an access token that is otherwise valid (correct signature, not
 * expired) must still be rejected once the session it is bound to has been revoked (logout on
 * another device, password change revoking other sessions, admin force-logout, etc).
 * <p>
 * 2. Suspended-user appeal access: a SUSPENDED user's access token must authenticate on
 * POST /appeals (and nowhere else) so they can submit an appeal, while BAN/DELETED accounts
 * (which throw the same AccountNotActiveException from loadUserById) must stay fully locked out
 * everywhere, including /appeals.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String TOKEN = "valid.jwt.token";
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private UserDetailsServiceImpl userDetailsService;
    @Mock private AdminJwtTokenProvider adminJwtTokenProvider;
    @Mock private AdminUserDetailsServiceImpl adminUserDetailsService;
    @Mock private SessionService sessionService;
    @Mock private FilterChain filterChain;
    @Mock private Claims claims;

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

    private MockHttpServletRequest requestWithBearerToken(String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(servletPath);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private UserDetails sampleUserDetails() {
        return User.builder().username(USER_ID).password("").authorities(List.of()).build();
    }

    // ── Session-scoped revocation ────────────────────────────────────────────

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

        UserDetails userDetails = User.builder()
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

    // ── Suspended-user appeal access ─────────────────────────────────────────

    @Test
    void suspendedUser_onAppealsPath_authenticatesViaPermissiveLoader() throws Exception {
        when(jwtTokenProvider.validateAccessToken(TOKEN)).thenReturn(true);
        when(jwtTokenProvider.parseAccessToken(TOKEN)).thenReturn(claims);
        when(claims.getSubject()).thenReturn(USER_ID);
        when(userDetailsService.loadUserById(USER_ID)).thenThrow(new AccountNotActiveException("Account is suspended"));
        when(userDetailsService.loadSuspendedUserById(USER_ID)).thenReturn(sampleUserDetails());

        MockHttpServletRequest request = requestWithBearerToken("/appeals");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(sampleUserDetails());
        verify(chain).doFilter(request, response);
    }

    @Test
    void suspendedUser_onOtherPath_isNotAuthenticated() throws Exception {
        when(jwtTokenProvider.validateAccessToken(TOKEN)).thenReturn(true);
        when(jwtTokenProvider.parseAccessToken(TOKEN)).thenReturn(claims);
        when(claims.getSubject()).thenReturn(USER_ID);
        when(userDetailsService.loadUserById(USER_ID)).thenThrow(new AccountNotActiveException("Account is suspended"));

        MockHttpServletRequest request = requestWithBearerToken("/contents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userDetailsService, never()).loadSuspendedUserById(anyString());
        verify(chain).doFilter(request, response);
    }

    @Test
    void bannedUser_onAppealsPath_isNotAuthenticated() throws Exception {
        when(jwtTokenProvider.validateAccessToken(TOKEN)).thenReturn(true);
        when(jwtTokenProvider.parseAccessToken(TOKEN)).thenReturn(claims);
        when(claims.getSubject()).thenReturn(USER_ID);
        when(userDetailsService.loadUserById(USER_ID)).thenThrow(new AccountNotActiveException("Account is ban"));
        when(userDetailsService.loadSuspendedUserById(USER_ID)).thenThrow(new AccountNotActiveException("Account is ban"));

        MockHttpServletRequest request = requestWithBearerToken("/appeals");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }
}
