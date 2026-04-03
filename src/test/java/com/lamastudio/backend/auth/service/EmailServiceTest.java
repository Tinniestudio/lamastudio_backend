package com.lamastudio.backend.auth.service;

import com.lamastudio.backend.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import com.lamastudio.backend.email.ResendEmailService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @org.mockito.Mock
    private ResendEmailService resendEmailService;
    private AppProperties appProperties;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setBaseUrl("http://localhost:8080");
        appProperties.setFrontendUrl("http://localhost:3000");
        emailService = new EmailService(resendEmailService, appProperties);
    }

    @Test
    @DisplayName("sendVerificationEmail builds correct link and sends message")
    void sendVerificationEmail() {
    emailService.sendVerificationEmail("user@example.com", "Jane Doe", "token123");

    ArgumentCaptor<com.lamastudio.backend.email.EmailRequest> captor = ArgumentCaptor.forClass(com.lamastudio.backend.email.EmailRequest.class);
    verify(resendEmailService).sendEmail(captor.capture());

    com.lamastudio.backend.email.EmailRequest req = captor.getValue();
    assertThat(req.to()).isEqualTo("user@example.com");
    assertThat(req.subject()).contains("Verify");
    assertThat(req.html()).contains("token123");
    assertThat(req.html()).contains("http://localhost:3000/verify-email?token=token123");
    }

    @Test
    @DisplayName("sendPasswordResetEmail builds correct link and sends message")
    void sendPasswordResetEmail() {
    emailService.sendPasswordResetEmail("user@example.com", "Jane Doe", "reset-token");

    ArgumentCaptor<com.lamastudio.backend.email.EmailRequest> captor = ArgumentCaptor.forClass(com.lamastudio.backend.email.EmailRequest.class);
    verify(resendEmailService).sendEmail(captor.capture());

    com.lamastudio.backend.email.EmailRequest req = captor.getValue();
    assertThat(req.to()).isEqualTo("user@example.com");
    assertThat(req.subject()).contains("Reset");
    assertThat(req.html()).contains("reset-token");
    assertThat(req.html()).contains("http://localhost:3000/reset-password?token=reset-token");
    }
}
