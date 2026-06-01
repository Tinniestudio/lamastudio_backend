package com.lamastudio.backend.shared.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "stripe")
@Getter
@Setter
public class StripeProperties {

    private String secretKey;
    private String webhookSecret;
    private String cdnBaseUrl;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }
}
