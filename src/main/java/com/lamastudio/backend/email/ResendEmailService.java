package com.lamastudio.backend.email;

import com.lamastudio.backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResendEmailService {

    private final WebClient resendWebClient;
    private final AppProperties appProperties;


    /**
     * Send email using Resend API. This is non-blocking — it subscribes to the request and logs result.
     */
    public void sendEmail(EmailRequest request) {
        Map<String, Object> payload = new HashMap<>();
        String configuredFrom = appProperties.getResend() != null ? appProperties.getResend().getFromEmail() : null;
        String from = (configuredFrom != null && !configuredFrom.isBlank()) ? configuredFrom
                : System.getenv().getOrDefault("RESEND_FROM_EMAIL", "no-reply@lamastudio.com");

        payload.put("from", from);
        payload.put("to", request.to());
        payload.put("subject", request.subject());
        payload.put("html", request.html());

        resendWebClient.post()
                .uri("/emails")
                .bodyValue(payload)
                .exchangeToMono(this::handleResponse)
                .doOnError(ex -> log.error("Failed to send email to {}: {}", request.to(), ex.getMessage()))
                .subscribe(result -> log.debug("Resend API response for {}: {}", request.to(), result));
    }

    private Mono<String> handleResponse(ClientResponse resp) {
        if (resp.statusCode().is2xxSuccessful()) {
            return resp.bodyToMono(String.class);
        }
        return resp.bodyToMono(String.class)
                .flatMap(body -> Mono.error(new RuntimeException("Resend API error: " + resp.statusCode() + " - " + body)));
    }
}
