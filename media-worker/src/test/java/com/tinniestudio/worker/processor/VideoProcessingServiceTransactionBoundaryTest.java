package com.tinniestudio.worker.processor;

import com.tinniestudio.worker.config.WorkerProperties;
import com.tinniestudio.worker.dto.MediaProcessingJobPayload;
import com.tinniestudio.worker.entity.VideoAsset;
import com.tinniestudio.worker.ffmpeg.FFmpegRunner;
import com.tinniestudio.worker.ffmpeg.FFprobeRunner;
import com.tinniestudio.worker.repository.ProcessingJobRepository;
import com.tinniestudio.worker.repository.VideoAssetRepository;
import com.tinniestudio.worker.repository.VideoVariantRepository;
import com.tinniestudio.worker.storage.WorkerStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Regression test for Bug 1: VideoProcessingService.process() used to be
 * {@code @Transactional} around the entire ffmpeg pipeline. When an exception was
 * thrown after markFailed() had already written processingStatus=FAILED, Spring rolled
 * back the whole transaction, erasing that write and reverting the asset to its
 * pre-processing status.
 *
 * <p>This uses a real (non-mocked) H2-backed Spring context so the actual
 * {@code @Transactional} proxy behavior is exercised — a plain Mockito unit test
 * cannot reproduce this bug because there's no transactional AOP proxy involved.
 *
 * <p>{@code @Transactional(propagation = NOT_SUPPORTED)} is applied to disable
 * {@code @DataJpaTest}'s own test-managed transaction (which by default wraps the whole
 * test method and rolls it back afterwards for isolation). We need process()'s writes
 * to really commit so we can prove they survive a later exception, not merely persist
 * inside one giant transaction that never actually commits.
 */
@DataJpaTest
@AutoConfigureTestEntityManager
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
    VideoProcessingService.class,
    VideoProcessingServiceTransactionBoundaryTest.TestConfig.class
})
class VideoProcessingServiceTransactionBoundaryTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        WorkerProperties workerProperties() {
            WorkerProperties props = new WorkerProperties();
            props.getProcessing().setTempDir(System.getProperty("java.io.tmpdir") + "/tinniestudio-tx-test");
            props.getProcessing().setMaxDurationSeconds(14_400);
            return props;
        }
    }

    @Autowired VideoAssetRepository videoAssetRepo;
    @Autowired ProcessingJobRepository processingJobRepo;
    @Autowired VideoVariantRepository videoVariantRepo;
    @Autowired VideoProcessingService service;

    @MockBean FFprobeRunner ffprobeRunner;
    @MockBean FFmpegRunner ffmpegRunner;
    @MockBean WorkerStorageService storageService;
    @MockBean RabbitTemplate rabbitTemplate;

    @Test
    void failedStatusWriteSurvivesExceptionThrownLaterInThePipeline() throws Exception {
        UUID assetId = UUID.randomUUID();

        VideoAsset asset = new VideoAsset();
        asset.setId(assetId);
        asset.setRawStorageKey("uploads/" + assetId + "/raw.mp4");
        asset.setOriginalFilename("raw.mp4");
        asset.setProcessingStatus("PENDING");
        asset.setProcessingAttempts(0);
        videoAssetRepo.saveAndFlush(asset);

        MediaProcessingJobPayload payload = new MediaProcessingJobPayload();
        payload.setJobId("job-" + assetId);
        payload.setVideoAssetId(assetId);
        payload.setStorageKey(asset.getRawStorageKey());

        // download() must actually create a file — process() reads its size (Files.size) for
        // byte-tracking immediately afterwards, before ever reaching ffprobe. A no-op mock would
        // make the pipeline fail at the size-check instead of at the intended ffprobe step.
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.write(target, new byte[]{1, 2, 3, 4});
            return null;
        }).when(storageService).download(anyString(), any(Path.class));
        when(ffprobeRunner.probe(anyString()))
            .thenThrow(new RuntimeException("ffprobe crashed mid-pipeline"));

        boolean threw = false;
        try {
            service.process(payload);
        } catch (VideoProcessingService.RetryableProcessingException expected) {
            threw = true;
        }
        assertThat(threw)
            .as("process() must rethrow so the consumer/RetryPublisher can decide retry vs DLQ")
            .isTrue();

        VideoAsset persisted = videoAssetRepo.findById(assetId).orElseThrow();
        assertThat(persisted.getProcessingStatus())
            .as("FAILED status written in the catch block must survive the exception, not revert to PENDING")
            .isEqualTo("FAILED");
        assertThat(persisted.getProcessingError()).contains("ffprobe crashed mid-pipeline");
    }
}
