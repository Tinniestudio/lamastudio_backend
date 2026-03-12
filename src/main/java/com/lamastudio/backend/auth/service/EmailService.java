package com.lamastudio.backend.auth.service;

import com.lamastudio.backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;

    @Async
    public void sendVerificationEmail(String to, String token) {
        String verifyUrl = appProperties.getBaseUrl() + "/api/v1/auth/verify-email?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Verify your LamaStudio email address");
        message.setText(
                "Welcome to LamaStudio!\n\n" +
                "Please verify your email address by clicking the link below:\n\n" +
                verifyUrl + "\n\n" +
                "This link expires in 24 hours.\n\n" +
                "If you did not create an account, you can safely ignore this email."
        );

        try {
            mailSender.send(message);
            log.debug("Verification email sent to: {}", to);
        } catch (Exception ex) {
            log.error("Failed to send verification email to {}: {}", to, ex.getMessage());
        }
    }

    @Async
    public void sendPasswordResetEmail(String to, String token) {
        String resetUrl = appProperties.getFrontendUrl() + "/auth/reset-password?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Reset your LamaStudio password");
        message.setText(
                "You requested a password reset for your LamaStudio account.\n\n" +
                "Click the link below to set a new password:\n\n" +
                resetUrl + "\n\n" +
                "This link expires in 1 hour.\n\n" +
                "If you did not request a password reset, please ignore this email and your password will remain unchanged."
        );

        try {
            mailSender.send(message);
            log.debug("Password reset email sent to: {}", to);
        } catch (Exception ex) {
            log.error("Failed to send password reset email to {}: {}", to, ex.getMessage());
        }
    }
}
