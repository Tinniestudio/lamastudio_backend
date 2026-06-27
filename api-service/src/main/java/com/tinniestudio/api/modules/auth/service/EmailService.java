package com.tinniestudio.api.modules.auth.service;

import com.tinniestudio.api.shared.Template.EmailTemplates;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.email.EmailRequest;
import com.tinniestudio.api.shared.email.ResendEmailService;

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

    @Async
    public void sendAdminPasswordResetEmail(String to, String token) {
        String resetUrl = appProperties.getFrontendUrl() + "/admin/reset-password?token=" + token;
        String html = "<p>Admin password reset requested. Use this link (expires in 15 minutes): "
                + "<a href=\"" + resetUrl + "\">" + resetUrl + "</a></p>";
        resendEmailService.sendEmail(new EmailRequest(to, "Admin password reset request", html));
    }

    @Async
    public void sendAdminPasswordResetAlert(String superAdminEmail, String requestingAdminEmail) {
        String html = "<p>A password reset was requested for admin account: <strong>"
                + requestingAdminEmail + "</strong>. If this was not expected, take action immediately.</p>";
        resendEmailService.sendEmail(
                new EmailRequest(superAdminEmail, "Security alert: Admin password reset requested", html));
    }

    @Async
    public void sendPasswordChangedEmail(String to, String name) {
        String html = "<p>Hi " + resolveName(name) + ",</p>"
            + "<p>Your password was successfully changed. If you did not make this change, please contact support immediately.</p>";
        resendEmailService.sendEmail(new EmailRequest(to, "Your password has been changed", html));
    }

    @Async
    public void sendSubscriptionActivatedEmail(String to, String name, String planName) {
        String html = "<p>Hi " + resolveName(name) + ",</p>"
            + "<p>Your <strong>" + planName + "</strong> subscription is now active. Enjoy!</p>";
        resendEmailService.sendEmail(new EmailRequest(to, "Subscription activated — " + planName, html));
    }

    @Async
    public void sendPaymentFailedEmail(String to, String name, String planName, String reason) {
        String html = "<p>Hi " + resolveName(name) + ",</p>"
            + "<p>Your payment for the <strong>" + planName + "</strong> plan could not be processed.</p>"
            + (reason != null ? "<p>Reason: " + reason + "</p>" : "")
            + "<p>Please update your payment method and try again.</p>";
        resendEmailService.sendEmail(new EmailRequest(to, "Payment failed — " + planName, html));
    }

    @Async
    public void sendSubscriptionCancelledEmail(String to, String name, String endDate) {
        String html = "<p>Hi " + resolveName(name) + ",</p>"
            + "<p>Your subscription has been cancelled. You retain full access until <strong>" + endDate + "</strong>.</p>";
        resendEmailService.sendEmail(new EmailRequest(to, "Subscription cancelled", html));
    }

    @Async
    public void sendSubscriptionExpiredEmail(String to, String name) {
        String html = "<p>Hi " + resolveName(name) + ",</p>"
            + "<p>Your subscription has expired. Upgrade to continue enjoying premium content.</p>";
        resendEmailService.sendEmail(new EmailRequest(to, "Your subscription has expired", html));
    }

    @Async
    public void sendSubscriptionExpiringEmail(String to, String name, String endDate, String planName) {
        String html = "<p>Hi " + resolveName(name) + ",</p>"
            + "<p>Your <strong>" + planName + "</strong> subscription expires on <strong>" + endDate + "</strong>.</p>"
            + "<p>Renew now to keep uninterrupted access.</p>";
        resendEmailService.sendEmail(new EmailRequest(to, "Your subscription expires soon", html));
    }

    private String resolveName(String name) {
        return StringUtils.hasText(name) ? name.strip() : "User";
    }

}
