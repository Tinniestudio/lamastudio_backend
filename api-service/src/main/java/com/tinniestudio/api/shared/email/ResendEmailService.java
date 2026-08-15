package com.tinniestudio.api.shared.email;

import com.tinniestudio.api.shared.config.AppProperties;
import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


@Service
@RequiredArgsConstructor
@Slf4j
public class ResendEmailService {

    private final Resend resendClient;
    private final AppProperties appProperties;


    /**
     * Send email using Resend API. This is non-blocking — it subscribes to the request and logs result.
     */
    public void sendEmail(EmailRequest request) {
        String apiKey = appProperties.getResend() != null ? appProperties.getResend().getApiKey() : null;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Resend API key is not configured; skipping email to {}", request.to());
            return;
        }

        String configuredFrom = appProperties.getResend() != null ? appProperties.getResend().getFromEmail() : null;
        String from = (configuredFrom != null && !configuredFrom.isBlank()) ? configuredFrom
                : System.getenv().getOrDefault("RESEND_FROM_EMAIL", "no-reply@tinniestudio.com");

    CreateEmailOptions params = CreateEmailOptions.builder()
        .from(from)
        .to(request.to())
        .subject(request.subject())
        .html(request.html())
        .build();

    Mono.fromCallable(() -> resendClient.emails().send(params))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            response -> log.debug("Resend API response for {}: {}", request.to(), response.getId()),
            ex -> log.error("Failed to send email to {}: {}", request.to(), ex.getMessage())
        );
    }
}
