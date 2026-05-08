package com.lamastudio.backend.modules.auth.service;

import com.lamastudio.backend.shared.Template.EmailTemplates;
import com.lamastudio.backend.shared.config.AppProperties;
import com.lamastudio.backend.shared.email.EmailRequest;
import com.lamastudio.backend.shared.email.ResendEmailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final ResendEmailService resendEmailService;
    private final AppProperties appProperties;

    @Async
    public void sendVerificationEmail(String to, String name, String token) {
        String verifyUrl = appProperties.getFrontendUrl() + "/verify-email?token=" + token;
        String logoUrl = appProperties.getFrontendUrl() + "/m_logo_y.png";

        String html = EmailTemplates.verificationTemplate(resolveName(name), verifyUrl, logoUrl);

        resendEmailService.sendEmail(
                new EmailRequest(to, "Verify your LamaStudio email address", html));
    }

    @Async
    public void sendPasswordResetEmail(String to, String name, String token) {
        String resetUrl = appProperties.getFrontendUrl() + "/reset-password?token=" + token;
        String logoUrl = appProperties.getFrontendUrl() + "/m_logo_y.png";

        String html = EmailTemplates.passwordResetTemplate(resolveName(name), resetUrl, logoUrl);

        resendEmailService.sendEmail(
                new EmailRequest(to, "Reset your LamaStudio password", html));
    }

    private String resolveName(String name) {
        return StringUtils.hasText(name) ? name.strip() : "User";
    }

}
