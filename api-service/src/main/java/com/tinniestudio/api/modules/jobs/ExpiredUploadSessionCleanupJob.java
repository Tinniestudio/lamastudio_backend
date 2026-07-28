package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Hourly: delete DB rows for UploadSessions that have passed their expiry
 * and were never completed. Storage objects are NOT removed so users can
 * resume (Batch 17 item 2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredUploadSessionCleanupJob {

    private final UploadSessionRepository uploadSessionRepo;
    private final JobLogger jobLogger;

    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "ExpiredUploadSessionCleanupJob", lockAtMostFor = "5m", lockAtLeastFor = "1m")
    public void run() {
        JobExecutionLog logEntry = jobLogger.start("ExpiredUploadSessionCleanupJob");
        try {
            int deleted = uploadSessionRepo.deleteExpiredNonCompleted(Instant.now(), UploadStatus.COMPLETED);
            jobLogger.success(logEntry, deleted);
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
