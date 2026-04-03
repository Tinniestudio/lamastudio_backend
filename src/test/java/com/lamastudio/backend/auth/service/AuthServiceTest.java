package com.lamastudio.backend.auth.service;

import com.lamastudio.backend.auth.dto.LoginRequest;
import com.lamastudio.backend.auth.dto.RegisterRequest;
import com.lamastudio.backend.auth.jwt.CookieFactory;
import com.lamastudio.backend.auth.jwt.JwtTokenProvider;
import com.lamastudio.backend.config.AppProperties;
import com.lamastudio.backend.exception.AccountNotActiveException;
import com.lamastudio.backend.exception.BadCredentialsException;
import com.lamastudio.backend.exception.EmailAlreadyExistsException;
import com.lamastudio.backend.role.entity.Role;
import com.lamastudio.backend.role.entity.RoleName;
import com.lamastudio.backend.role.repository.RoleRepository;
import com.lamastudio.backend.user.entity.AccountStatus;
import com.lamastudio.backend.user.entity.AuthProvider;
import com.lamastudio.backend.user.entity.User;
import com.lamastudio.backend.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private CookieFactory cookieFactory;
    @Mock private EmailService emailService;

    private AppProperties appProperties;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        appProperties = buildAppProperties();
        authService = new AuthService(
                userRepository,
                roleRepository,
                passwordEncoder,
                authenticationManager,
                jwtTokenProvider,
                cookieFactory,
                emailService,
                appProperties
        );
    }

    @Test
    @DisplayName("register: success assigns role, saves user, sends email and sets cookies")
    void register_success() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@example.com");
        request.setPassword("Password1!");
        request.setFirstName("Jane");
        request.setLastName("Doe");

        Role userRole = new Role(RoleName.ROLE_USER);

        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(roleRepository.findByName(RoleName.ROLE_USER)).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode("Password1!"))
                .thenReturn("encoded");
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> {
                    User u = invocation.getArgument(0);
                    u.setId(UUID.randomUUID());
                    return u;
                });
        when(jwtTokenProvider.generateAccessToken(any(User.class))).thenReturn("access");
        when(jwtTokenProvider.generateRefreshToken(any(User.class))).thenReturn("refresh");

        HttpServletResponse response = new MockHttpServletResponse();

        var authResponse = authService.register(request, response);

        assertThat(authResponse.getEmail()).isEqualTo("test@example.com");
        assertThat(authResponse.getRoles()).contains(RoleName.ROLE_USER.name());
        verify(userRepository).existsByEmail("test@example.com");
        verify(roleRepository).findByName(RoleName.ROLE_USER);
        verify(passwordEncoder).encode("Password1!");
        verify(userRepository).save(any(User.class));
        verify(emailService).sendVerificationEmail(eq("test@example.com"), any());
        verify(cookieFactory).addAuthCookies(eq(response), eq("access"), eq("refresh"));
    }

    @Test
    @DisplayName("register: duplicate email throws EmailAlreadyExistsException")
    void register_duplicateEmail() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("exists@example.com");
        when(userRepository.existsByEmail("exists@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request, new MockHttpServletResponse()))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository).existsByEmail("exists@example.com");
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    @DisplayName("login: success authenticates, sets cookies, returns response")
    void login_success() {
        User user = buildActiveUser();
        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword("Password1!");

        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(mock(Authentication.class));
        when(jwtTokenProvider.generateAccessToken(user)).thenReturn("access");
        when(jwtTokenProvider.generateRefreshToken(user)).thenReturn("refresh");

        HttpServletResponse response = new MockHttpServletResponse();

        var authResponse = authService.login(request, response);

        assertThat(authResponse.getEmail()).isEqualTo(user.getEmail());
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        verify(cookieFactory).addAuthCookies(eq(response), eq("access"), eq("refresh"));
    }

    @Test
    @DisplayName("login: inactive account throws AccountNotActiveException")
    void login_inactive() {
        User user = buildActiveUser();
        user.setAccountStatus(AccountStatus.SUSPENDED);

        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword("Password1!");

    when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
        .thenThrow(new DisabledException("Account is disabled"));

        assertThatThrownBy(() -> authService.login(request, new MockHttpServletResponse()))
                .isInstanceOf(AccountNotActiveException.class);

    verify(authenticationManager).authenticate(any());
    }

    @Test
    @DisplayName("login: wrong password raises BadCredentialsException")
    void login_wrongPassword() {
        User user = buildActiveUser();
        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword("bad");
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(mock(AuthenticationException.class));

        assertThatThrownBy(() -> authService.login(request, new MockHttpServletResponse()))
                .isInstanceOf(BadCredentialsException.class);
    }

    private User buildActiveUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setPasswordHash("hash");
        user.setProvider(AuthProvider.LOCAL);
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setAccountStatus(AccountStatus.ACTIVE);
        user.setRoles(Set.of(new Role(RoleName.ROLE_USER)));
        return user;
    }

    private AppProperties buildAppProperties() {
        AppProperties props = new AppProperties();
        AppProperties.Jwt jwt = new AppProperties.Jwt();
        AppProperties.Jwt.TokenConfig access = new AppProperties.Jwt.TokenConfig();
        access.setSecret("dGhpc2lzbG9uZ2FjY2Vzc3NlY3JldGtleTEyMzQ1Njc4OTA=");
        access.setExpirationMs(900_000L);
        AppProperties.Jwt.TokenConfig refresh = new AppProperties.Jwt.TokenConfig();
        refresh.setSecret("dGhpc2lzcmVmcmVzaHNlY3JldGtleTEyMzQ1Njc4OTA=");
        refresh.setExpirationMs(604_800_000L);
        jwt.setAccessToken(access);
        jwt.setRefreshToken(refresh);
        jwt.setIssuer("lamastudio-test");
        props.setJwt(jwt);
        AppProperties.Cookie cookie = new AppProperties.Cookie();
        cookie.setSecure(false);
        cookie.setSameSite("Lax");
        props.setCookie(cookie);
        props.setBaseUrl("http://localhost:8080");
        props.setFrontendUrl("http://localhost:3000");
        return props;
    }
}
