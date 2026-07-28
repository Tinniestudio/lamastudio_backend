package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Daily at 03:00 UTC: permanently delete VideoAssets that have been in FAILED
 * status for more than 7 days (Batch 17 item 5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FailedVideoAssetCleanupJob {

    private final VideoAssetRepository videoAssetRepo;
    private final JobLogger jobLogger;

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "FailedVideoAssetCleanupJob", lockAtMostFor = "15m", lockAtLeastFor = "1m")
    public void run() {
        JobExecutionLog logEntry = jobLogger.start("FailedVideoAssetCleanupJob");
        try {
            Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
            int deleted = videoAssetRepo
                    .deleteByProcessingStatusAndUpdatedAtBefore(ProcessingStatus.FAILED, cutoff);
            jobLogger.success(logEntry, deleted);
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
