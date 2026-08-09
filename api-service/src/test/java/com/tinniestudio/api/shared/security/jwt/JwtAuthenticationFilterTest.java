package com.tinniestudio.api.shared.security.jwt;

import com.tinniestudio.api.modules.auth.admin.service.AdminUserDetailsServiceImpl;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.exception.AccountNotActiveException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A SUSPENDED user's access token must authenticate on POST /appeals (and nowhere else) so they
 * can submit an appeal, while BAN/DELETED accounts (which throw the same AccountNotActiveException
 * from loadUserById) must stay fully locked out everywhere, including /appeals.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock UserDetailsServiceImpl userDetailsService;
    @Mock AdminJwtTokenProvider adminJwtTokenProvider;
    @Mock AdminUserDetailsServiceImpl adminUserDetailsService;
    @Mock Claims claims;

    JwtAuthenticationFilter filter;

    private static final String TOKEN = "test-token";
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService, adminJwtTokenProvider, adminUserDetailsService);
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
