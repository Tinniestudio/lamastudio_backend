package com.tinniestudio.worker.processor;

import com.tinniestudio.worker.config.WorkerProperties;
import com.tinniestudio.worker.dto.MediaProcessingJobPayload;
import com.tinniestudio.worker.entity.ProcessingJob;
import com.tinniestudio.worker.entity.VideoAsset;
import com.tinniestudio.worker.entity.VideoVariant;
import com.tinniestudio.worker.ffmpeg.*;
import com.tinniestudio.worker.repository.ProcessingJobRepository;
import com.tinniestudio.worker.repository.VideoAssetRepository;
import com.tinniestudio.worker.repository.VideoVariantRepository;
import com.tinniestudio.worker.storage.WorkerStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoProcessingServiceTest {

    @Mock VideoAssetRepository videoAssetRepo;
    @Mock VideoVariantRepository videoVariantRepo;
    @Mock ProcessingJobRepository processingJobRepo;
    @Mock FFprobeRunner ffprobeRunner;
    @Mock FFmpegRunner ffmpegRunner;
    @Mock WorkerStorageService storageService;
    @Mock RabbitTemplate rabbitTemplate;

    private WorkerProperties workerProperties;
    private VideoProcessingService service;

    @BeforeEach
    void setUp() {
        workerProperties = new WorkerProperties();
        workerProperties.getProcessing().setTempDir("/tmp/tinniestudio-test");
        workerProperties.getProcessing().setMaxDurationSeconds(14400);
        service = new VideoProcessingService(
            videoAssetRepo, videoVariantRepo, processingJobRepo,
            ffprobeRunner, ffmpegRunner, storageService, workerProperties, rabbitTemplate
        );
    }

    private MediaProcessingJobPayload buildPayload(UUID assetId) {
        MediaProcessingJobPayload p = new MediaProcessingJobPayload();
        p.setJobId("job-" + assetId);
        p.setVideoAssetId(assetId);
        p.setStorageKey("uploads/" + assetId + "/raw.mp4");
        return p;
    }

    private VideoAsset buildAsset(UUID id) {
        VideoAsset a = new VideoAsset();
        a.setId(id);
        a.setRawStorageKey("uploads/" + id + "/raw.mp4");
        a.setOriginalFilename("raw.mp4");
        a.setProcessingStatus("PENDING");
        a.setProcessingAttempts(0);
        return a;
    }

    /**
     * storageService is mocked, so download() is a no-op by default and never
     * actually places a file on disk at the target path. VideoProcessingService
     * now stats the downloaded file's size right after download, so any test
     * that exercises the real download step needs a real file to stat.
     */
    private void stubDownloadCreatesFile(long sizeBytes) throws Exception {
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.write(target, new byte[(int) sizeBytes]);
            return null;
        }).when(storageService).download(anyString(), any(Path.class));
    }

    @Nested
    class process {

        @Test
        void updatesVideoAssetToReadyOnSuccess() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE"))
                .thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(12_345L);

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                120, 1920, 1080, "h264", 5_000_000L, true);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(meta);

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getProcessingStatus()).isEqualTo("READY");
            assertThat(last.getManifestUrl()).contains("master.m3u8");
            assertThat(last.getDurationSeconds()).isEqualTo(120);
        }

        @Test
        void writesMainVideoOutputUnderProcessedMainPrefix() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            asset.setAssetType("MAIN_VIDEO");
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE"))
                .thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(12_345L);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(
                new FFprobeRunner.VideoMetadata(120, 1920, 1080, "h264", 5_000_000L, true));

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getManifestUrl()).isEqualTo("processed/main/" + assetId + "/master.m3u8");
        }

        @Test
        void writesTrailerOutputUnderProcessedTrailersPrefix_soCloudFrontCanLeaveItPublic() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            asset.setAssetType("TRAILER");
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE"))
                .thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(12_345L);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(
                new FFprobeRunner.VideoMetadata(30, 1920, 1080, "h264", 5_000_000L, true));

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getManifestUrl()).isEqualTo("processed/trailers/" + assetId + "/master.m3u8");
        }

        @Test
        void requiresAudioWhenProbingAMainVideo() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            asset.setAssetType("MAIN_VIDEO");
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(12_345L);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(
                new FFprobeRunner.VideoMetadata(120, 1920, 1080, "h264", 5_000_000L, true));

            service.process(payload);

            verify(ffprobeRunner).probe(anyString(), eq(true));
        }

        @Test
        void doesNotRequireAudioWhenProbingATrailer() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            asset.setAssetType("TRAILER");
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(12_345L);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(
                new FFprobeRunner.VideoMetadata(30, 1920, 1080, "h264", 5_000_000L, false));

            service.process(payload);

            verify(ffprobeRunner).probe(anyString(), eq(false));
        }

        @Test
        void setsFileSizeBytesFromDownloadedFileOnSuccess() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE"))
                .thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            long expectedBytes = 54_321L;
            stubDownloadCreatesFile(expectedBytes);

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                30, 640, 360, "h264", 1_000_000L, true);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(meta);

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getFileSizeBytes()).isEqualTo(expectedBytes);
        }

        @Test
        void isIdempotentWhenJobAlreadyDone() throws Exception {
            UUID assetId = UUID.randomUUID();
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE"))
                .thenReturn(true);

            service.process(payload);

            verify(videoAssetRepo, never()).findById(any());
            verify(ffprobeRunner, never()).probe(any(), anyBoolean());
        }

        @Test
        void createsVideoVariantRecordsForEachResolution() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(anyString(), eq("DONE"))).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                60, 1280, 720, "h264", 2_800_000L, true);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(meta);

            service.process(payload);

            // 720p source -> 3 variants (720p, 480p, 360p)
            verify(videoVariantRepo, times(3)).save(any(VideoVariant.class));
        }

        @Test
        void scalesTranscodeTimeoutToTripleSourceDurationWhenLongerThanDefault() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            // 20-minute (1200s) source: 1200*3=3600s exceeds the 1800s default timeout.
            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                1200, 640, 360, "h264", 800_000L, true);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(meta);

            service.process(payload);

            ArgumentCaptor<Long> timeoutCaptor = ArgumentCaptor.forClass(Long.class);
            verify(ffmpegRunner).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), timeoutCaptor.capture());
            assertThat(timeoutCaptor.getValue()).isEqualTo(3600L);
        }

        @Test
        void keepsDefaultTranscodeTimeoutWhenSourceIsShort() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            // 30s trailer: 30*3=90s is well under the 1800s default, so the default wins.
            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                30, 640, 360, "h264", 800_000L, true);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(meta);

            service.process(payload);

            ArgumentCaptor<Long> timeoutCaptor = ArgumentCaptor.forClass(Long.class);
            verify(ffmpegRunner).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), timeoutCaptor.capture());
            assertThat(timeoutCaptor.getValue()).isEqualTo(1800L);
        }

        @Test
        void setsFailedStatusWhenDurationExceedsMax() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            workerProperties.getProcessing().setMaxDurationSeconds(3600);

            when(processingJobRepo.existsByJobIdAndStatus(anyString(), eq("DONE"))).thenReturn(false);
            when(processingJobRepo.existsByJobIdAndStatus(anyString(), eq("FAILED"))).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);
            // 5-hour video exceeds the 1-hour max
            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                18000, 1920, 1080, "h264", 5_000_000L, true);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(meta);

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getProcessingStatus()).isEqualTo("FAILED");
            assertThat(last.getProcessingError()).contains("exceeds max allowed");
        }

        @Test
        void setsFailedStatusOnNonRetryableError() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(anyString(), eq("DONE"))).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);
            when(ffprobeRunner.probe(anyString(), anyBoolean()))
                .thenThrow(new FFprobeRunner.ValidationException("no audio stream found"));

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getProcessingStatus()).isEqualTo("FAILED");
            assertThat(last.getProcessingError()).contains("no audio stream");
        }

        @Test
        void stopsBeforeTranscodingWhenCancelledDuringEarlierStages() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            VideoAsset cancelledAsset = buildAsset(assetId);
            cancelledAsset.setProcessingStatus("CANCELLED");
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            // 1st findById: initial load at the top of process(). 2nd: the cancellation check
            // before the first (and, at 1080p source, only-first-of-four) transcode tier.
            when(videoAssetRepo.findById(assetId))
                .thenReturn(Optional.of(asset))
                .thenReturn(Optional.of(cancelledAsset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                60, 1920, 1080, "h264", 5_000_000L, true);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(meta);

            service.process(payload);

            verify(ffmpegRunner, never()).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyLong());
            verify(storageService, never()).uploadDirectory(anyString(), any(Path.class));
            verify(videoAssetRepo, never()).save(argThat(a -> "READY".equals(a.getProcessingStatus())));
        }

        @Test
        void stopsBeforeUploadingWhenCancelledAfterTranscoding() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            VideoAsset cancelledAsset = buildAsset(assetId);
            cancelledAsset.setProcessingStatus("CANCELLED");
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            // 360p source -> exactly 1 tier (see ResolutionLadder), so the in-loop check only
            // fires once (not cancelled yet); the pre-upload check is what catches it.
            // 1st findById: initial load. 2nd: in-loop check before the single 360p tier
            // (not cancelled). 3rd: the pre-UPLOADING_OUTPUT check (cancelled).
            when(videoAssetRepo.findById(assetId))
                .thenReturn(Optional.of(asset))
                .thenReturn(Optional.of(asset))
                .thenReturn(Optional.of(cancelledAsset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                30, 640, 360, "h264", 800_000L, true);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(meta);

            service.process(payload);

            verify(ffmpegRunner, times(1)).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyLong());
            verify(storageService, never()).uploadDirectory(anyString(), any(Path.class));
            verify(videoVariantRepo, never()).save(any(VideoVariant.class));
            verify(videoAssetRepo, never()).save(argThat(a -> "READY".equals(a.getProcessingStatus())));
        }

        @Test
        void reusesExistingProcessingJobRowOnASecondCallWithTheSameJobIdInsteadOfInsertingADuplicate() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            // Deliberately stub the terminal-skip guard to false regardless of what gets
            // written internally — this isolates getOrCreateJob()'s reuse behavior from the
            // separate "don't write FAILED prematurely" fix (a later task), which this test
            // doesn't depend on.
            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "FAILED")).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));

            java.util.concurrent.atomic.AtomicReference<ProcessingJob> savedJob =
                new java.util.concurrent.atomic.AtomicReference<>();
            when(processingJobRepo.findByJobId(payload.getJobId()))
                .thenAnswer(inv -> Optional.ofNullable(savedJob.get()));
            when(processingJobRepo.save(any())).thenAnswer(inv -> {
                ProcessingJob j = inv.getArgument(0);
                savedJob.set(j);
                return j;
            });
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(
                new FFprobeRunner.VideoMetadata(30, 640, 360, "h264", 800_000L, true));

            service.process(payload);
            ProcessingJob jobAfterFirstCall = savedJob.get();
            assertThat(jobAfterFirstCall).isNotNull();

            service.process(payload);
            ProcessingJob jobAfterSecondCall = savedJob.get();

            assertThat(jobAfterSecondCall)
                .as("the second call must reuse the same ProcessingJob entity, not construct a new one "
                    + "(which would violate the DB-level unique constraint on jobId)")
                .isSameAs(jobAfterFirstCall);
            assertThat(jobAfterSecondCall.getAttempt()).isEqualTo(2);
        }

        @Test
        void retryableFailureFollowedBySuccessfulRetryActuallyReExecutesInsteadOfBeingSkipped() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            // With the fix, a retryable failure never writes job.status="FAILED", so this guard
            // must keep returning false across both attempts — reproducing the original bug would
            // require it to flip true after attempt 1, which is exactly what the fix prevents.
            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "FAILED")).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));

            java.util.concurrent.atomic.AtomicReference<ProcessingJob> savedJob =
                new java.util.concurrent.atomic.AtomicReference<>();
            when(processingJobRepo.findByJobId(payload.getJobId()))
                .thenAnswer(inv -> Optional.ofNullable(savedJob.get()));
            when(processingJobRepo.save(any())).thenAnswer(inv -> {
                ProcessingJob j = inv.getArgument(0);
                savedJob.set(j);
                return j;
            });
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                60, 1280, 720, "h264", 2_800_000L, true);
            // Attempt 1: ffprobe throws a retryable error. Attempt 2 (same jobId): succeeds.
            when(ffprobeRunner.probe(anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("transient ffprobe failure"))
                .thenReturn(meta);

            // Attempt 1
            assertThatThrownBy(() -> service.process(payload))
                .isInstanceOf(VideoProcessingService.RetryableProcessingException.class);

            assertThat(savedJob.get().getStatus())
                .as("a retryable failure must not write a terminal job status")
                .isNotEqualTo("FAILED");
            assertThat(savedJob.get().getErrorMessage()).contains("transient ffprobe failure");
            verify(videoAssetRepo, never()).save(argThat(a -> "FAILED".equals(a.getProcessingStatus())));
            ProcessingJob jobAfterAttempt1 = savedJob.get();

            // Attempt 2 — must actually re-run ffprobe/transcode, not be silently skipped.
            service.process(payload);

            verify(ffprobeRunner, times(2)).probe(anyString(), anyBoolean());
            assertThat(savedJob.get())
                .as("attempt 2 must reuse the same ProcessingJob row, not insert a duplicate")
                .isSameAs(jobAfterAttempt1);
            assertThat(savedJob.get().getStatus()).isEqualTo("DONE");
            assertThat(savedJob.get().getAttempt()).isEqualTo(2);
            verify(videoAssetRepo, atLeastOnce()).save(argThat(a -> "READY".equals(a.getProcessingStatus())));
        }
    }
}
