package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.auth.user.repository.UserSessionRepository;
import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.modules.notification.repository.NotificationRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.storage.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Background Jobs")
class BackgroundJobsTest {

    @Mock UploadSessionRepository uploadSessionRepo;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock StorageService storageService;
    @Mock NotificationRepository notificationRepo;
    @Mock UserSessionRepository userSessionRepo;
    @Mock JobLogger jobLogger;

    @InjectMocks ExpiredUploadSessionCleanupJob expiredUploadJob;
    @InjectMocks StaleVideoAssetJob staleVideoJob;
    @InjectMocks FailedVideoAssetCleanupJob failedVideoJob;
    @InjectMocks NotificationCleanupJob notificationCleanupJob;
    @InjectMocks ExpiredSessionCleanupJob expiredSessionJob;

    private JobExecutionLog fakeLog(String name) {
        JobExecutionLog l = new JobExecutionLog();
        l.setJobName(name);
        l.setStatus("RUNNING");
        return l;
    }

    // ─── ExpiredUploadSessionCleanupJob ──────────────────────────────────────

    @Test
    @DisplayName("expired upload session job deletes non-completed rows and logs success")
    void expiredUploadSessionJob_deletesAndLogs() {
        when(jobLogger.start(any())).thenReturn(fakeLog("ExpiredUploadSessionCleanupJob"));
        when(uploadSessionRepo.deleteExpiredNonCompleted(any(), eq(UploadStatus.COMPLETED))).thenReturn(3);

        expiredUploadJob.run();

        verify(uploadSessionRepo).deleteExpiredNonCompleted(any(), eq(UploadStatus.COMPLETED));
        verify(jobLogger).success(any(), eq(3));
        verify(jobLogger, never()).failure(any(), any());
    }

    @Test
    @DisplayName("expired upload session job records failure when repo throws")
    void expiredUploadSessionJob_repoThrows_logsFailure() {
        when(jobLogger.start(any())).thenReturn(fakeLog("ExpiredUploadSessionCleanupJob"));
        when(uploadSessionRepo.deleteExpiredNonCompleted(any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        expiredUploadJob.run();

        verify(jobLogger).failure(any(), eq("DB error"));
        verify(jobLogger, never()).success(any(), anyInt());
    }

    // ─── StaleVideoAssetJob ───────────────────────────────────────────────────

    @Test
    @DisplayName("stale video asset job atomically transitions stuck PROCESSING assets to FAILED")
    void staleVideoAssetJob_marksFailedAndLogs() {
        when(jobLogger.start(any())).thenReturn(fakeLog("StaleVideoAssetJob"));
        when(videoAssetRepo.transitionStaleProcessingAssets(
                eq(ProcessingStatus.PROCESSING), eq(ProcessingStatus.FAILED), any()))
                .thenReturn(1);

        staleVideoJob.run();

        verify(videoAssetRepo).transitionStaleProcessingAssets(
                eq(ProcessingStatus.PROCESSING), eq(ProcessingStatus.FAILED), any());
        // No read-then-save: the job must never load entities and call save() —
        // that's exactly the race the atomic UPDATE replaces.
        verify(videoAssetRepo, never()).save(any());
        verify(jobLogger).success(any(), eq(1));
    }

    @Test
    @DisplayName("stale video asset job logs zero when nothing is stale")
    void staleVideoAssetJob_nothingStale_logsZero() {
        when(jobLogger.start(any())).thenReturn(fakeLog("StaleVideoAssetJob"));
        when(videoAssetRepo.transitionStaleProcessingAssets(any(), any(), any()))
                .thenReturn(0);

        staleVideoJob.run();

        verify(jobLogger).success(any(), eq(0));
    }

    @Test
    @DisplayName("stale video asset job records failure when repo throws")
    void staleVideoAssetJob_repoThrows_logsFailure() {
        when(jobLogger.start(any())).thenReturn(fakeLog("StaleVideoAssetJob"));
        when(videoAssetRepo.transitionStaleProcessingAssets(any(), any(), any()))
                .thenThrow(new RuntimeException("timeout"));

        staleVideoJob.run();

        verify(jobLogger).failure(any(), eq("timeout"));
    }

    // ─── FailedVideoAssetCleanupJob ───────────────────────────────────────────

    @Test
    @DisplayName("failed video asset cleanup job deletes storage object before/alongside DB row, then logs")
    void failedVideoAssetCleanupJob_deletesStorageAndRowsAndLogs() {
        VideoAsset asset1 = new VideoAsset();
        asset1.setId(UUID.randomUUID());
        asset1.setStorageKey("raw/asset-1.mp4");
        VideoAsset asset2 = new VideoAsset();
        asset2.setId(UUID.randomUUID());
        asset2.setStorageKey("raw/asset-2.mp4");

        when(jobLogger.start(any())).thenReturn(fakeLog("FailedVideoAssetCleanupJob"));
        when(videoAssetRepo.findByProcessingStatusAndUpdatedAtBefore(eq(ProcessingStatus.FAILED), any()))
                .thenReturn(List.of(asset1, asset2));

        failedVideoJob.run();

        InOrder inOrder = inOrder(storageService, videoAssetRepo);
        inOrder.verify(storageService).deleteObject("raw/asset-1.mp4");
        inOrder.verify(storageService).deleteObject("raw/asset-2.mp4");
        inOrder.verify(videoAssetRepo).deleteAllByIdInBatch(anyList());

        verify(videoAssetRepo).deleteAllByIdInBatch(List.of(asset1.getId(), asset2.getId()));
        verify(jobLogger).success(any(), eq(2));
    }

    @Test
    @DisplayName("failed video asset cleanup job skips storage/DB delete when nothing is stale")
    void failedVideoAssetCleanupJob_nothingStale_skipsDeletes() {
        when(jobLogger.start(any())).thenReturn(fakeLog("FailedVideoAssetCleanupJob"));
        when(videoAssetRepo.findByProcessingStatusAndUpdatedAtBefore(eq(ProcessingStatus.FAILED), any()))
                .thenReturn(List.of());

        failedVideoJob.run();

        verify(storageService, never()).deleteObject(any());
        verify(videoAssetRepo, never()).deleteAllByIdInBatch(any());
        verify(jobLogger).success(any(), eq(0));
    }

    @Test
    @DisplayName("failed video asset cleanup job still deletes DB row when storage delete fails")
    void failedVideoAssetCleanupJob_storageDeleteThrows_stillDeletesRowAndLogsSuccess() {
        VideoAsset asset = new VideoAsset();
        asset.setId(UUID.randomUUID());
        asset.setStorageKey("raw/broken.mp4");

        when(jobLogger.start(any())).thenReturn(fakeLog("FailedVideoAssetCleanupJob"));
        when(videoAssetRepo.findByProcessingStatusAndUpdatedAtBefore(eq(ProcessingStatus.FAILED), any()))
                .thenReturn(List.of(asset));
        doThrow(new RuntimeException("storage unreachable"))
                .when(storageService).deleteObject("raw/broken.mp4");

        failedVideoJob.run();

        verify(videoAssetRepo).deleteAllByIdInBatch(List.of(asset.getId()));
        verify(jobLogger).success(any(), eq(1));
    }

    // ─── NotificationCleanupJob ───────────────────────────────────────────────

    @Test
    @DisplayName("notification cleanup job deletes notifications older than 90 days and logs")
    void notificationCleanupJob_deletesOldAndLogs() {
        when(jobLogger.start(any())).thenReturn(fakeLog("NotificationCleanupJob"));
        when(notificationRepo.deleteOlderThan(any())).thenReturn(42);

        notificationCleanupJob.run();

        verify(notificationRepo).deleteOlderThan(any());
        verify(jobLogger).success(any(), eq(42));
    }

    @Test
    @DisplayName("notification cleanup job records failure when repo throws")
    void notificationCleanupJob_repoThrows_logsFailure() {
        when(jobLogger.start(any())).thenReturn(fakeLog("NotificationCleanupJob"));
        when(notificationRepo.deleteOlderThan(any())).thenThrow(new RuntimeException("conn lost"));

        notificationCleanupJob.run();

        verify(jobLogger).failure(any(), eq("conn lost"));
    }

    // ─── ExpiredSessionCleanupJob ─────────────────────────────────────────────

    @Test
    @DisplayName("expired session cleanup job deletes sessions past expiry and logs")
    void expiredSessionCleanupJob_deletesAndLogs() {
        when(jobLogger.start(any())).thenReturn(fakeLog("ExpiredSessionCleanupJob"));
        when(userSessionRepo.deleteExpiredSessions(any())).thenReturn(7);

        expiredSessionJob.run();

        verify(userSessionRepo).deleteExpiredSessions(any());
        verify(jobLogger).success(any(), eq(7));
    }

    @Test
    @DisplayName("expired session cleanup job records failure when repo throws")
    void expiredSessionCleanupJob_repoThrows_logsFailure() {
        when(jobLogger.start(any())).thenReturn(fakeLog("ExpiredSessionCleanupJob"));
        when(userSessionRepo.deleteExpiredSessions(any())).thenThrow(new RuntimeException("lock fail"));

        expiredSessionJob.run();

        verify(jobLogger).failure(any(), eq("lock fail"));
    }
}
