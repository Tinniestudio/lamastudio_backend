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
            when(ffprobeRunner.probe(anyString())).thenReturn(meta);

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getProcessingStatus()).isEqualTo("READY");
            assertThat(last.getManifestUrl()).contains("master.m3u8");
            assertThat(last.getDurationSeconds()).isEqualTo(120);
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
            when(ffprobeRunner.probe(anyString())).thenReturn(meta);

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
            verify(ffprobeRunner, never()).probe(any());
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
            when(ffprobeRunner.probe(anyString())).thenReturn(meta);

            service.process(payload);

            // 720p source -> 3 variants (720p, 480p, 360p)
            verify(videoVariantRepo, times(3)).save(any(VideoVariant.class));
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
            when(ffprobeRunner.probe(anyString())).thenReturn(meta);

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
            when(ffprobeRunner.probe(anyString()))
                .thenThrow(new FFprobeRunner.ValidationException("no audio stream found"));

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getProcessingStatus()).isEqualTo("FAILED");
            assertThat(last.getProcessingError()).contains("no audio stream");
        }
    }
}
