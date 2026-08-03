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
 * Every 10 minutes: find VideoAssets that have been stuck in PROCESSING
 * for more than 60 minutes and mark them FAILED (Batch 17 item 4).
 *
 * <p>Uses a single atomic conditional UPDATE (guarded by a WHERE clause that
 * re-checks processingStatus at write time) instead of a read-then-save loop,
 * so a concurrent worker transition to COMPLETED/FAILED between this job's
 * read and write can never be clobbered by a blind overwrite.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleVideoAssetJob {

    private final VideoAssetRepository videoAssetRepo;
    private final JobLogger jobLogger;

    @Scheduled(fixedDelay = 600_000)
    @SchedulerLock(name = "StaleVideoAssetJob", lockAtMostFor = "8m", lockAtLeastFor = "1m")
    public void run() {
        JobExecutionLog logEntry = jobLogger.start("StaleVideoAssetJob");
        try {
            Instant cutoff = Instant.now().minus(60, ChronoUnit.MINUTES);
            int updated = videoAssetRepo.transitionStaleProcessingAssets(
                    ProcessingStatus.PROCESSING, ProcessingStatus.FAILED, cutoff);

            log.warn("[StaleVideoAssetJob] Marked {} asset(s) as FAILED (stale for >60 min)", updated);
            jobLogger.success(logEntry, updated);
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
