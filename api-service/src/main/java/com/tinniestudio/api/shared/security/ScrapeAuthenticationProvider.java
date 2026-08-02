package com.tinniestudio.api.shared.security;

import com.tinniestudio.api.shared.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Authenticates the Prometheus scraper hitting /actuator/prometheus via HTTP Basic, against a
 * single static credential pair from AppProperties (env-configured, not the Admin/User JWT
 * system — a scraper is a long-lived service identity, not a human session with a 15-minute
 * token). If either configured credential is blank, authentication always fails closed rather
 * than silently matching an empty value.
 *
 * <p>Deliberately NOT a Spring-managed bean (no {@code @Component}): registering a second
 * {@code AuthenticationProvider} bean in the context — even one only wired into one chain's own
 * ProviderManager — breaks SecurityConfig's global {@code authenticationManager()} @Bean
 * (backed by {@code AuthenticationConfiguration.getAuthenticationManager()}), causing a circular
 * lookup that surfaces as a StackOverflowError on the real user login endpoint. Constructed
 * directly by SecurityConfig instead, which already has AppProperties injected.
 */
@RequiredArgsConstructor
public class ScrapeAuthenticationProvider implements AuthenticationProvider {

    private final AppProperties appProperties;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());

        String expectedUsername = appProperties.getMetrics().getScrapeUsername();
        String expectedPassword = appProperties.getMetrics().getScrapePassword();

        if (expectedUsername == null || expectedUsername.isBlank()
                || expectedPassword == null || expectedPassword.isBlank()) {
            throw new BadCredentialsException("Prometheus scrape credentials are not configured");
        }
        if (!expectedUsername.equals(username) || !expectedPassword.equals(password)) {
            throw new BadCredentialsException("Invalid scrape credentials");
        }

        return new UsernamePasswordAuthenticationToken(
                username, password, List.of(new SimpleGrantedAuthority("ROLE_SCRAPER")));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
