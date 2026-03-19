package com.lamastudio.backend.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ResendConfig {

    @Value("${resend.api-key:}")
    private String apiKey;

    @Bean
    public WebClient resendWebClient() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(conf -> conf.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Accept", "application/json")
                .exchangeStrategies(strategies)
                .build();
    }
}
