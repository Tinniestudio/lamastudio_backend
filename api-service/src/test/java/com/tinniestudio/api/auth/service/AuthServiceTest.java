package com.tinniestudio.api.auth.service;

import com.tinniestudio.api.modules.auth.dto.LoginRequest;
import com.tinniestudio.api.modules.auth.dto.RegisterRequest;
import com.tinniestudio.api.modules.auth.exception.BadCredentialsException;
import com.tinniestudio.api.modules.auth.exception.EmailAlreadyExistsException;
import com.tinniestudio.api.modules.auth.service.AuthService;
import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.modules.auth.user.dto.AuthProfileResponse;
import com.tinniestudio.api.modules.auth.user.service.AuthProfileService;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.billing.repository.SubscriptionPlanRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.role.repository.RoleRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.AuthProvider;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.Role;
import com.tinniestudio.api.shared.entity.RoleName;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.AccountNotActiveException;
import com.tinniestudio.api.shared.security.jwt.CookieFactory;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
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
    @Mock private SessionService sessionService;
    @Mock private UserSubscriptionRepository userSubscriptionRepository;
    @Mock private SubscriptionPlanRepository subscriptionPlanRepository;
    @Mock private AuthProfileService authProfileService;
    @Mock private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
    @Mock private com.tinniestudio.api.shared.cache.CacheService cacheService;

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
                appProperties,
                sessionService,
                userSubscriptionRepository,
                subscriptionPlanRepository,
                authProfileService,
                cacheService,
                rabbitTemplate
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
        UUID sessionId = UUID.randomUUID();

        when(userRepository.existsByEmailAndDeletedAtIsNull("test@example.com")).thenReturn(false);
        when(roleRepository.findByName(RoleName.ROLE_USER)).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode("Password1!")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(sessionService.createSession(any(), any(), any())).thenReturn(sessionId);
        when(jwtTokenProvider.generateAccessToken(any(User.class), any(UUID.class))).thenReturn("access");
        when(jwtTokenProvider.generateRefreshToken(any(User.class), any(UUID.class))).thenReturn("refresh");
        when(subscriptionPlanRepository.findByNameIgnoreCase("FREE")).thenReturn(Optional.empty());
        when(authProfileService.getProfile(any(UUID.class), any(UUID.class), any()))
            .thenReturn(AuthProfileResponse.builder()
                .email("test@example.com")
                .roles(Set.of(RoleName.ROLE_USER.name()))
                .provider("LOCAL").emailVerified(false).build());

        HttpServletResponse response = new MockHttpServletResponse();
        var authResponse = authService.register(request, response);

        assertThat(authResponse.getEmail()).isEqualTo("test@example.com");
        assertThat(authResponse.getRoles()).contains(RoleName.ROLE_USER.name());
        verify(userRepository).existsByEmailAndDeletedAtIsNull("test@example.com");
        verify(passwordEncoder).encode("Password1!");
        verify(userRepository).save(any(User.class));
        verify(emailService).sendVerificationEmail(eq("test@example.com"), eq("Jane Doe"), any());
    }

    @Test
    @DisplayName("register: duplicate email throws EmailAlreadyExistsException")
    void register_duplicateEmail() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("exists@example.com");
        when(userRepository.existsByEmailAndDeletedAtIsNull("exists@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request, new MockHttpServletResponse()))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository).existsByEmailAndDeletedAtIsNull("exists@example.com");
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    @DisplayName("login: success authenticates, sets cookies, returns response")
    void login_success() {
        User user = buildActiveUser();
        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword("Password1!");

        UUID sessionId = UUID.randomUUID();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(mock(Authentication.class));
        when(sessionService.resolveMaxDevicesForUser(user.getId())).thenReturn(3);
        when(sessionService.countActiveSessions(user.getId())).thenReturn(0L);
        when(sessionService.createSession(any(), any(), any())).thenReturn(sessionId);
        when(jwtTokenProvider.generateAccessToken(any(User.class), any(UUID.class))).thenReturn("access");
        when(jwtTokenProvider.generateRefreshToken(any(User.class), any(UUID.class))).thenReturn("refresh");
        when(authProfileService.getProfile(any(UUID.class), any(UUID.class), any()))
            .thenReturn(AuthProfileResponse.builder()
                .email(user.getEmail())
                .provider("LOCAL").emailVerified(true).build());

        HttpServletRequest httpRequest = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        var authResponse = authService.login(request, httpRequest, response);

        assertThat(authResponse.getEmail()).isEqualTo(user.getEmail());
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        verify(sessionService).createSession(eq(user.getId()), any(), any());
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

        assertThatThrownBy(() -> authService.login(request, new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(AccountNotActiveException.class);

        verify(authenticationManager).authenticate(any());
    }

    @Test
    @DisplayName("login: wrong password raises BadCredentialsException")
    void login_wrongPassword() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("bad");
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(mock(AuthenticationException.class));

        assertThatThrownBy(() -> authService.login(request, new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("login: throws DeviceLimitReachedException when already at the device cap, and does NOT create a session")
    void login_atDeviceLimit_throwsWithoutCreatingSession() {
        com.tinniestudio.api.modules.auth.dto.LoginRequest request = new com.tinniestudio.api.modules.auth.dto.LoginRequest();
        request.setEmail("jane@example.com");
        request.setPassword("Password1!");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        user.setProvider(AuthProvider.LOCAL);
        user.setAccountStatus(AccountStatus.ACTIVE);

        Authentication authentication = mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(sessionService.resolveMaxDevicesForUser(user.getId())).thenReturn(1);
        when(sessionService.countActiveSessions(user.getId())).thenReturn(1L);
        when(jwtTokenProvider.generateDeviceLimitResolutionToken(user.getId())).thenReturn("resolution-token");
        when(sessionService.getActiveSessions(user.getId())).thenReturn(java.util.List.of());

        HttpServletRequest httpRequest = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> authService.login(request, httpRequest, response))
                .isInstanceOf(com.tinniestudio.api.modules.auth.exception.DeviceLimitReachedException.class);

        verify(sessionService, never()).createSession(any(), any(), any());
    }

    @Test
    @DisplayName("login: under the device cap creates a session as before")
    void login_underDeviceLimit_createsSessionNormally() {
        com.tinniestudio.api.modules.auth.dto.LoginRequest request = new com.tinniestudio.api.modules.auth.dto.LoginRequest();
        request.setEmail("jane@example.com");
        request.setPassword("Password1!");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        user.setProvider(AuthProvider.LOCAL);
        user.setAccountStatus(AccountStatus.ACTIVE);
        UUID sessionId = UUID.randomUUID();

        Authentication authentication = mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(sessionService.resolveMaxDevicesForUser(user.getId())).thenReturn(3);
        when(sessionService.countActiveSessions(user.getId())).thenReturn(1L);
        when(sessionService.createSession(eq(user.getId()), any(), any())).thenReturn(sessionId);
        when(authProfileService.getProfile(eq(user.getId()), eq(sessionId), any()))
                .thenReturn(AuthProfileResponse.builder()
                        .email("jane@example.com")
                        .roles(Set.of(RoleName.ROLE_USER.name()))
                        .provider("LOCAL").emailVerified(true).build());

        HttpServletRequest httpRequest = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        AuthProfileResponse result = authService.login(request, httpRequest, response);

        assertThat(result.getEmail()).isEqualTo("jane@example.com");
        verify(jwtTokenProvider, never()).generateDeviceLimitResolutionToken(any());
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
        jwt.setIssuer("tinniestudio-test");
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
