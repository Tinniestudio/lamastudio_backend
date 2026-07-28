package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.VideoAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Every 10 minutes: find VideoAssets that have been stuck in PROCESSING
 * for more than 60 minutes and mark them FAILED (Batch 17 item 4).
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
            List<VideoAsset> stale = videoAssetRepo
                    .findByProcessingStatusAndUpdatedAtBefore(ProcessingStatus.PROCESSING, cutoff);

            for (VideoAsset asset : stale) {
                asset.setProcessingStatus(ProcessingStatus.FAILED);
                videoAssetRepo.save(asset);
                log.warn("[StaleVideoAssetJob] Marked asset {} as FAILED (stale for >60 min)", asset.getId());
            }

            jobLogger.success(logEntry, stale.size());
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
