package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.billing.service.SubscriptionService;
import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.shared.entity.UserSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Daily: charges every subscription whose nextRenewalAt has passed. Each subscription is
 * processed independently inside SubscriptionService.processRenewal (its own row lock + its own
 * transaction) so one failure never stops the rest of the batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringBillingRenewalJob {

    private final UserSubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final JobLogger jobLogger;

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "RecurringBillingRenewalJob", lockAtMostFor = "50m", lockAtLeastFor = "1m")
    public void run() {
        JobExecutionLog logEntry = jobLogger.start("RecurringBillingRenewalJob");
        int processed = 0;
        try {
            List<UserSubscription> due = subscriptionRepository
                .findByAutoRenewTrueAndNextRenewalAtLessThanEqual(Instant.now());
            for (UserSubscription sub : due) {
                try {
                    subscriptionService.processRenewal(sub.getId());
                } catch (Exception ex) {
                    log.error("Renewal processing failed for subscription {}: {}", sub.getId(), ex.getMessage(), ex);
                }
                processed++;
            }
            jobLogger.success(logEntry, processed);
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
