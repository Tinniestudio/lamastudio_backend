package com.lamastudio.backend.shared.email;

import com.lamastudio.backend.shared.config.AppProperties;
import com.resend.Resend;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class ResendConfig {

    private final AppProperties appProperties;

    public ResendConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Bean
    public Resend resendClient() {
        String apiKey = appProperties.getResend().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Resend API key is not configured; email sending will be disabled.");
            return new Resend("");
        }

        return new Resend(apiKey);
    }
}
