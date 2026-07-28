package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.auth.user.repository.UserSessionRepository;
import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.modules.notification.repository.NotificationRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus;
import com.tinniestudio.api.shared.entity.VideoAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Background Jobs")
class BackgroundJobsTest {

    @Mock UploadSessionRepository uploadSessionRepo;
    @Mock VideoAssetRepository videoAssetRepo;
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
    @DisplayName("stale video asset job marks stuck PROCESSING assets as FAILED")
    void staleVideoAssetJob_marksFailedAndLogs() {
        VideoAsset asset = new VideoAsset();
        asset.setProcessingStatus(ProcessingStatus.PROCESSING);

        when(jobLogger.start(any())).thenReturn(fakeLog("StaleVideoAssetJob"));
        when(videoAssetRepo.findByProcessingStatusAndUpdatedAtBefore(
                eq(ProcessingStatus.PROCESSING), any())).thenReturn(List.of(asset));

        staleVideoJob.run();

        verify(videoAssetRepo).save(asset);
        verify(jobLogger).success(any(), eq(1));
    }

    @Test
    @DisplayName("stale video asset job logs zero when nothing is stale")
    void staleVideoAssetJob_nothingStale_logsZero() {
        when(jobLogger.start(any())).thenReturn(fakeLog("StaleVideoAssetJob"));
        when(videoAssetRepo.findByProcessingStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of());

        staleVideoJob.run();

        verify(videoAssetRepo, never()).save(any());
        verify(jobLogger).success(any(), eq(0));
    }

    @Test
    @DisplayName("stale video asset job records failure when repo throws")
    void staleVideoAssetJob_repoThrows_logsFailure() {
        when(jobLogger.start(any())).thenReturn(fakeLog("StaleVideoAssetJob"));
        when(videoAssetRepo.findByProcessingStatusAndUpdatedAtBefore(any(), any()))
                .thenThrow(new RuntimeException("timeout"));

        staleVideoJob.run();

        verify(jobLogger).failure(any(), eq("timeout"));
    }

    // ─── FailedVideoAssetCleanupJob ───────────────────────────────────────────

    @Test
    @DisplayName("failed video asset cleanup job deletes assets older than 7 days and logs")
    void failedVideoAssetCleanupJob_deletesAndLogs() {
        when(jobLogger.start(any())).thenReturn(fakeLog("FailedVideoAssetCleanupJob"));
        when(videoAssetRepo.deleteByProcessingStatusAndUpdatedAtBefore(
                eq(ProcessingStatus.FAILED), any())).thenReturn(5);

        failedVideoJob.run();

        verify(videoAssetRepo).deleteByProcessingStatusAndUpdatedAtBefore(eq(ProcessingStatus.FAILED), any());
        verify(jobLogger).success(any(), eq(5));
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
