package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.billing.service.SubscriptionService;
import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.shared.entity.DomainEnums.PaymentStatus;
import com.tinniestudio.api.shared.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Every 5 minutes: fallback sweep for PENDING Payments whose Stripe Checkout Session has passed
 * its own recorded expiry (Payment.expiresAt) but never got a checkout.session.expired webhook
 * (missed delivery, downtime, etc). This only ever catches what the webhook fast path
 * (StripeWebhookController) missed — on the normal path the Payment is already FAILED by the time
 * this job runs, so a typical run processes zero items.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingPaymentExpiryJob {

    private final PaymentRepository paymentRepository;
    private final SubscriptionService subscriptionService;
    private final JobLogger jobLogger;

    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "PendingPaymentExpiryJob", lockAtMostFor = "4m", lockAtLeastFor = "1m")
    public void run() {
        JobExecutionLog logEntry = jobLogger.start("PendingPaymentExpiryJob");
        try {
            List<Payment> expired = paymentRepository.findExpiredPending(PaymentStatus.PENDING, Instant.now());
            for (Payment payment : expired) {
                subscriptionService.failPayment(payment.getProviderReference(), "Checkout session expired (fallback sweep)");
            }
            jobLogger.success(logEntry, expired.size());
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
