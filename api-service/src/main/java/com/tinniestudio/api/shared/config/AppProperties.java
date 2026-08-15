package com.tinniestudio.api.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private String baseUrl;
    private String frontendUrl;

    private Jwt jwt = new Jwt();
    private AdminJwt adminJwt = new AdminJwt();
    private Cookie cookie = new Cookie();
    private EmailVerification emailVerification = new EmailVerification();
    private AdminPasswordReset adminPasswordReset = new AdminPasswordReset();
    private PasswordReset passwordReset = new PasswordReset();
    private Cors cors = new Cors();
    private Resend resend = new Resend();
    private String adminBootstrapToken;
    private int freeTierContentLimit = 2;
    private Cdn cdn = new Cdn();
    private Metrics metrics = new Metrics();

    /**
     * Static Basic-auth credentials for the Prometheus scraper hitting /actuator/prometheus.
     * Deliberately separate from the Admin/User JWT system — a scraper is a long-lived service
     * identity, not a human session, so it can't rely on short-lived JWTs. Left blank by default
     * (no local-dev friction); if either value is blank, ScrapeAuthenticationProvider rejects the
     * request rather than silently accepting an empty credential.
     */
    @Getter
    @Setter
    public static class Metrics {
        private String scrapeUsername;
        private String scrapePassword;
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();
        private List<String> allowedOriginPatterns = new ArrayList<>();
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        private List<String> allowedHeaders = List.of("*");
        private boolean allowCredentials = true;
        private long maxAge = 3600;
    }

    @Getter
    @Setter
    public static class Jwt {
        private TokenConfig accessToken = new TokenConfig();
        private TokenConfig refreshToken = new TokenConfig();
        private String issuer = "tinniestudio";

        @Getter
        @Setter
        public static class TokenConfig {
            private String secret;
            private long expirationMs;
        }
    }

    @Getter
    @Setter
    public static class AdminJwt {
        private TokenConfig accessToken = new TokenConfig();
        private TokenConfig refreshToken = new TokenConfig();

        @Getter
        @Setter
        public static class TokenConfig {
            private String secret;
            private long expirationMs;
        }
    }

    @Getter
    @Setter
    public static class AdminPasswordReset {
        private int tokenExpiryMinutes = 15;
    }

    @Getter
    @Setter
    public static class Cookie {
        private boolean secure = true;
        private String sameSite = "Lax";
        private String domain;
    }

    @Getter
    @Setter
    public static class EmailVerification {
        private int tokenExpiryHours = 24;
    }

    @Getter
    @Setter
    public static class PasswordReset {
        private int tokenExpiryHours = 1;
    }

    @Getter
    @Setter
    public static class Resend {
        private String apiKey;
        private String baseUrl;
        private String fromEmail;
    }

    @Getter
    @Setter
    public static class Cdn {
        private String baseUrl = "http://localhost:3000";
    }
}
