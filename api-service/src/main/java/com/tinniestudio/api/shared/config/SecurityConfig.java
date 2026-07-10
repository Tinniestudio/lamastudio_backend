package com.tinniestudio.api.shared.config;

import com.tinniestudio.api.modules.auth.admin.service.AdminUserDetailsServiceImpl;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.*;
import com.tinniestudio.api.shared.security.oauth.CustomOAuth2AuthorizationRequestResolver;
import com.tinniestudio.api.shared.security.oauth.OAuth2AuthenticationFailureHandler;
import com.tinniestudio.api.shared.security.oauth.OAuth2AuthenticationSuccessHandler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    // ── Stateless / non-JPA dependencies — eager injection is safe ───────────
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final AppProperties appProperties;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final AdminJwtTokenProvider adminJwtTokenProvider;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

    // ── JPA-dependent beans — @Lazy defers creation until after EntityManagerFactory is ready
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsServiceImpl userDetailsService;
    private final AdminUserDetailsServiceImpl adminUserDetailsService;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public SecurityConfig(
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler,
            AppProperties appProperties,
            ClientRegistrationRepository clientRegistrationRepository,
            AdminJwtTokenProvider adminJwtTokenProvider,
            OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler,
            OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler,
            @Lazy JwtAuthenticationFilter jwtAuthenticationFilter,
            @Lazy UserDetailsServiceImpl userDetailsService,
            @Lazy AdminUserDetailsServiceImpl adminUserDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.jwtAccessDeniedHandler = jwtAccessDeniedHandler;
        this.appProperties = appProperties;
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.adminJwtTokenProvider = adminJwtTokenProvider;
        this.oAuth2AuthenticationSuccessHandler = oAuth2AuthenticationSuccessHandler;
        this.oAuth2AuthenticationFailureHandler = oAuth2AuthenticationFailureHandler;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.userDetailsService = userDetailsService;
        this.adminUserDetailsService = adminUserDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    // ── Admin filter chain — @Order(1) covers /auth/admin/** ──────────────────

    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        AdminJwtAuthenticationFilter adminFilter =
                new AdminJwtAuthenticationFilter(adminJwtTokenProvider, adminUserDetailsService);

        http
            .securityMatcher("/auth/admin/**")
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/auth/admin/login",
                    "/auth/admin/refresh",
                    "/auth/admin/bootstrap",
                    "/auth/admin/forgot-password",
                    "/auth/admin/reset-password"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                .accessDeniedHandler(jwtAccessDeniedHandler)
            )
            .addFilterBefore(adminFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // ── User filter chain — @Order(2) covers all other paths ─────────────────

    private static final String[] PUBLIC_ENDPOINTS = {
        "/",
        "/auth/register",
        "/auth/login",
        "/auth/refresh",
        "/auth/verify-email",
        "/auth/forgot-password",
        "/auth/reset-password",
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/verify-email",
        "/api/v1/auth/forgot-password",
        "/api/v1/auth/reset-password",
        "/login/oauth2/code/**",
        "/oauth2/**",
        "/auth/oauth2/**",
        "/api/v1/auth/oauth2/**",
        "/api/v1/oauth2/**",
        "/auth/oauth2/authorize/**",
        "/api/v1/auth/oauth2/authorize/**",
        "/categories",
        "/categories/**",
        "/api/v1/categories",
        "/api/v1/categories/**",
        "/subscriptions/plans",
        "/api/v1/subscriptions/plans",
        "/webhooks/stripe",
        "/api/v1/webhooks/stripe",
        "/actuator/health",
        "/swagger-ui.html",
        "/swagger-ui",
        "/swagger-ui/**",
        "/api-docs",
        "/api-docs/**",
        "/api-docs.yaml",
        "/v3/api-docs",
        "/v3/api-docs/**"
    };

    @Bean
    @Order(2)
    public SecurityFilterChain userSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                .accessDeniedHandler(jwtAccessDeniedHandler)
            )
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(endpoint ->
                    endpoint
                        .baseUri("/auth/oauth2/authorize")
                        .authorizationRequestResolver(customOAuth2AuthorizationRequestResolver())
                )
                .redirectionEndpoint(endpoint ->
                    endpoint.baseUri("/auth/oauth2/callback/*")
                )
                .successHandler(oAuth2AuthenticationSuccessHandler)
                .failureHandler(oAuth2AuthenticationFailureHandler)
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CustomOAuth2AuthorizationRequestResolver customOAuth2AuthorizationRequestResolver() {
        return new CustomOAuth2AuthorizationRequestResolver(clientRegistrationRepository);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        var cors = appProperties.getCors();
        configuration.setAllowedOrigins(cors.getAllowedOrigins());
        configuration.setAllowedMethods(cors.getAllowedMethods());
        configuration.setAllowedHeaders(cors.getAllowedHeaders());
        configuration.setAllowCredentials(cors.isAllowCredentials());
        configuration.setMaxAge(cors.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
