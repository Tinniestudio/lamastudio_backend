package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.modules.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Daily at 02:00 UTC: delete Notifications older than 90 days
 * (Batch 15 item 9).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupJob {

    private final NotificationRepository notificationRepo;
    private final JobLogger jobLogger;

    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "NotificationCleanupJob", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void run() {
        JobExecutionLog logEntry = jobLogger.start("NotificationCleanupJob");
        try {
            Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
            int deleted = notificationRepo.deleteOlderThan(cutoff);
            jobLogger.success(logEntry, deleted);
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
