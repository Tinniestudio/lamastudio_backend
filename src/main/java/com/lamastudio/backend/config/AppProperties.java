package com.lamastudio.backend.config;

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
    private Cookie cookie = new Cookie();
    private EmailVerification emailVerification = new EmailVerification();
    private PasswordReset passwordReset = new PasswordReset();
    private Cors cors = new Cors();
    private Resend resend = new Resend();

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();
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
        private String issuer = "lamastudio";

        @Getter
        @Setter
        public static class TokenConfig {
            private String secret;
            private long expirationMs;
        }
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
}
