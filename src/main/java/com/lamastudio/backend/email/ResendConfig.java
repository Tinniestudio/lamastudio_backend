package com.lamastudio.backend.email;

import com.lamastudio.backend.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ResendConfig {

    private final AppProperties appProperties;

    public ResendConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Bean
    public WebClient resendWebClient() {
        String apiKey = appProperties.getResend().getApiKey();

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(conf -> conf.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader("Accept", "application/json")
                .exchangeStrategies(strategies);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }

        return builder.build();
    }
}
