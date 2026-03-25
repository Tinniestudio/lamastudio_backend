package com.lamastudio.backend.auth.service;

import com.lamastudio.backend.config.AppProperties;
import com.lamastudio.backend.email.EmailRequest;
import com.lamastudio.backend.email.ResendEmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final ResendEmailService resendEmailService;
    private final AppProperties appProperties;

    @Async
    public void sendVerificationEmail(String to, String token) {
        String verifyUrl = appProperties.getFrontendUrl() + "/verify-email?token=" + token;

        String html = "<p>Welcome to LamaStudio!</p>" +
                "<p>Please verify your email address by clicking the link below:</p>" +
                "<p><a href=\"" + verifyUrl + "\">Verify email</a></p>" +
                "<p>This link expires in 24 hours.</p>";

        try {
            resendEmailService.sendEmail(new EmailRequest(to, "Verify your LamaStudio email address", html));
            log.debug("Verification email sent to: {}", to);
        } catch (Exception ex) {
            log.error("Failed to send verification email to {}: {}", to, ex.getMessage());
        }
    }

    @Async
    public void sendPasswordResetEmail(String to, String token) {
        String resetUrl = appProperties.getFrontendUrl() + "/reset-password?token=" + token;

        String html = "<p>You requested a password reset for your LamaStudio account.</p>" +
                "<p>Click the link below to set a new password:</p>" +
                "<p><a href=\"" + resetUrl + "\">Reset password</a></p>" +
                "<p>This link expires in 1 hour.</p>";

        try {
            resendEmailService.sendEmail(new EmailRequest(to, "Reset your LamaStudio password", html));
            log.debug("Password reset email sent to: {}", to);
        } catch (Exception ex) {
            log.error("Failed to send password reset email to {}: {}", to, ex.getMessage());
        }
    }
}
