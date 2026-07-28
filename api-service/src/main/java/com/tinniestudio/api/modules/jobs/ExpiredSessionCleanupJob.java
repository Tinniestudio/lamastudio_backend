package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.auth.user.repository.UserSessionRepository;
import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Daily at 01:00 UTC: hard-delete user_sessions rows whose expires_at has
 * already passed (Batch 17 item 7).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredSessionCleanupJob {

    private final UserSessionRepository userSessionRepo;
    private final JobLogger jobLogger;

    @Scheduled(cron = "0 0 1 * * *")
    @SchedulerLock(name = "ExpiredSessionCleanupJob", lockAtMostFor = "5m", lockAtLeastFor = "1m")
    public void run() {
        JobExecutionLog logEntry = jobLogger.start("ExpiredSessionCleanupJob");
        try {
            int deleted = userSessionRepo.deleteExpiredSessions(Instant.now());
            jobLogger.success(logEntry, deleted);
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
