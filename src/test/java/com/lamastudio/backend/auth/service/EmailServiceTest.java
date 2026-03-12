package com.lamastudio.backend.auth.service;

import com.lamastudio.backend.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private AppProperties appProperties;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setBaseUrl("http://localhost:8080");
        appProperties.setFrontendUrl("http://localhost:3000");
        emailService = new EmailService(mailSender, appProperties);
    }

    @Test
    @DisplayName("sendVerificationEmail builds correct link and sends message")
    void sendVerificationEmail() {
        emailService.sendVerificationEmail("user@example.com", "token123");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getTo()).containsExactly("user@example.com");
        assertThat(msg.getSubject()).contains("Verify");
        assertThat(msg.getText()).contains("token123");
        assertThat(msg.getText()).contains("http://localhost:8080/api/v1/auth/verify-email?token=token123");
    }

    @Test
    @DisplayName("sendPasswordResetEmail builds correct link and sends message")
    void sendPasswordResetEmail() {
        emailService.sendPasswordResetEmail("user@example.com", "reset-token");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getTo()).containsExactly("user@example.com");
        assertThat(msg.getSubject()).contains("Reset");
        assertThat(msg.getText()).contains("reset-token");
        assertThat(msg.getText()).contains("http://localhost:3000/auth/reset-password?token=reset-token");
    }
}
