package com.tinniestudio.api.shared.security;

import com.tinniestudio.api.shared.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScrapeAuthenticationProviderTest {

    private AppProperties appProperties;
    private ScrapeAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        provider = new ScrapeAuthenticationProvider(appProperties);
    }

    @Test
    void matchingCredentials_authenticatesWithScraperRole() {
        appProperties.getMetrics().setScrapeUsername("prometheus-scraper");
        appProperties.getMetrics().setScrapePassword("s3cr3t");

        Authentication result = provider.authenticate(
                new UsernamePasswordAuthenticationToken("prometheus-scraper", "s3cr3t"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_SCRAPER");
    }

    @Test
    void wrongPassword_throwsBadCredentials() {
        appProperties.getMetrics().setScrapeUsername("prometheus-scraper");
        appProperties.getMetrics().setScrapePassword("s3cr3t");

        assertThatThrownBy(() -> provider.authenticate(
                new UsernamePasswordAuthenticationToken("prometheus-scraper", "wrong")))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unconfiguredCredentials_failsClosed_evenWithEmptyEmptyMatch() {
        // appProperties.metrics left at defaults (blank username/password)
        assertThatThrownBy(() -> provider.authenticate(
                new UsernamePasswordAuthenticationToken("", "")))
            .isInstanceOf(BadCredentialsException.class);
    }
}
