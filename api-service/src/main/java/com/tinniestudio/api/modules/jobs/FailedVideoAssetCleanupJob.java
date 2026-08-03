package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Daily at 03:00 UTC: permanently delete VideoAssets that have been in FAILED
 * status for more than 7 days (Batch 17 item 5), along with their underlying
 * storage object. Unlike expired-UploadSession cleanup (which is intentionally
 * DB-row-only so the user can resume), a FAILED VideoAsset has no resume path,
 * so the raw storage object is actually removed here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FailedVideoAssetCleanupJob {

    private final VideoAssetRepository videoAssetRepo;
    private final StorageService storageService;
    private final JobLogger jobLogger;

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "FailedVideoAssetCleanupJob", lockAtMostFor = "15m", lockAtLeastFor = "1m")
    public void run() {
        JobExecutionLog logEntry = jobLogger.start("FailedVideoAssetCleanupJob");
        try {
            Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
            List<VideoAsset> toDelete = videoAssetRepo
                    .findByProcessingStatusAndUpdatedAtBefore(ProcessingStatus.FAILED, cutoff);

            for (VideoAsset asset : toDelete) {
                try {
                    storageService.deleteObject(asset.getStorageKey());
                } catch (Exception e) {
                    log.warn("[FailedVideoAssetCleanupJob] Failed to delete storage object {} for asset {}: {}",
                            asset.getStorageKey(), asset.getId(), e.getMessage());
                }
            }

            if (!toDelete.isEmpty()) {
                List<UUID> ids = toDelete.stream().map(VideoAsset::getId).toList();
                videoAssetRepo.deleteAllByIdInBatch(ids);
            }

            jobLogger.success(logEntry, toDelete.size());
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
