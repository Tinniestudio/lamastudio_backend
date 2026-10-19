package com.tinniestudio.worker.processor;

import com.tinniestudio.worker.config.WorkerProperties;
import com.tinniestudio.worker.dto.MediaProcessingJobPayload;
import com.tinniestudio.worker.entity.ProcessingJob;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        when(ffprobeRunner.probe(anyString(), anyBoolean()))
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

    /**
     * Regression test for Task 2: getOrCreateJob() looks up the existing row by jobId and
     * reuses it instead of blindly inserting, so that redelivery of the same jobId (e.g. the
     * worker process dying mid-pipeline before writing a terminal DONE/FAILED status, followed
     * by RabbitMQ redelivering the same message) doesn't crash on ProcessingJob.jobId's unique
     * constraint. A mocked-repository test can't prove this: Mockito's {@code save()} is a
     * no-op pass-through, whereas real Hibernate runs a detached entity with a non-null id
     * through {@code EntityManager.merge()} — this test uses the real H2-backed repositories
     * from {@code @DataJpaTest} so that merge path, and the real unique constraint, are both
     * actually exercised.
     */
    @Test
    void secondProcessCallWithSameJobIdDoesNotViolateTheUniqueJobIdConstraint() throws Exception {
        UUID assetId = UUID.randomUUID();

        VideoAsset asset = new VideoAsset();
        asset.setId(assetId);
        asset.setRawStorageKey("uploads/" + assetId + "/raw.mp4");
        asset.setOriginalFilename("raw.mp4");
        asset.setProcessingStatus("PENDING");
        asset.setProcessingAttempts(0);
        videoAssetRepo.saveAndFlush(asset);

        String jobId = "job-" + assetId;
        MediaProcessingJobPayload payload = new MediaProcessingJobPayload();
        payload.setJobId(jobId);
        payload.setVideoAssetId(assetId);
        payload.setStorageKey(asset.getRawStorageKey());

        // Same download stub as above: process() reads the downloaded file's size immediately
        // after download(), so a no-op mock would fail before ever reaching ffprobe.
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.write(target, new byte[]{1, 2, 3, 4});
            return null;
        }).when(storageService).download(anyString(), any(Path.class));
        // Unlike the test above, ffprobe must succeed here — this test exercises the happy
        // path twice. ffmpegRunner's transcode/generateThumbnail and storageService's
        // uploadDirectory/upload are void methods with no stubbed behavior, so Mockito's
        // default no-op applies and the pipeline runs through to completion untouched.
        when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(
            new FFprobeRunner.VideoMetadata(30, 640, 360, "h264", 800_000L, true));

        // --- First delivery: runs the full pipeline to completion, creating the job row.
        service.process(payload);

        ProcessingJob firstJob = processingJobRepo.findByJobId(jobId).orElseThrow();
        assertThat(firstJob.getAttempt()).isEqualTo(1);
        assertThat(firstJob.getStatus()).isEqualTo("DONE");

        // process() skips re-processing outright when the job already reached a terminal
        // DONE/FAILED status (see the existsByJobIdAndStatus guard at the top of process()).
        // That guard only protects against duplicate delivery AFTER a terminal outcome was
        // recorded; it does nothing for the case getOrCreateJob exists for — a redelivery that
        // arrives while the job is still in-flight (e.g. the worker process was killed mid-
        // pipeline before it ever wrote a terminal status). Flip the row back to a non-terminal
        // in-flight status to reproduce exactly that scenario and drive process() into
        // getOrCreateJob's reuse path a second time.
        firstJob.setStatus("TRANSCODING");
        processingJobRepo.saveAndFlush(firstJob);

        // --- Second delivery of the SAME jobId: must reuse the existing row via merge(),
        // not attempt an INSERT that would violate ProcessingJob.jobId's unique constraint.
        service.process(payload);

        List<ProcessingJob> rowsForJobId = processingJobRepo.findAll().stream()
            .filter(j -> jobId.equals(j.getJobId()))
            .toList();
        assertThat(rowsForJobId)
            .as("retrying the same jobId must reuse the existing row, never insert a duplicate")
            .hasSize(1);
        assertThat(rowsForJobId.get(0).getAttempt())
            .as("getOrCreateJob must bump attempt on the reused row rather than resetting it")
            .isEqualTo(2);
        assertThat(rowsForJobId.get(0).getStatus()).isEqualTo("DONE");
    }
}
