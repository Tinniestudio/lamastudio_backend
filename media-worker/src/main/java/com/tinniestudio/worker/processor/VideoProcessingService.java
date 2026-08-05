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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessingService {

    private final VideoAssetRepository videoAssetRepo;
    private final VideoVariantRepository videoVariantRepo;
    private final ProcessingJobRepository processingJobRepo;
    private final FFprobeRunner ffprobeRunner;
    private final FFmpegRunner ffmpegRunner;
    private final WorkerStorageService storageService;
    private final WorkerProperties workerProperties;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public void process(MediaProcessingJobPayload payload) {
        String jobId = payload.getJobId();
        UUID videoAssetId = payload.getVideoAssetId();

        if (processingJobRepo.existsByJobIdAndStatus(jobId, "DONE")
                || processingJobRepo.existsByJobIdAndStatus(jobId, "FAILED")) {
            log.info("Job {} already terminal, skipping", jobId);
            return;
        }

        VideoAsset asset = videoAssetRepo.findById(videoAssetId)
            .orElseThrow(() -> new IllegalStateException("VideoAsset not found: " + videoAssetId));

        ProcessingJob job = createJob(jobId, videoAssetId, asset.getProcessingAttempts() + 1);
        asset.setProcessingStatus("PROCESSING");
        asset.setProcessingAttempts(asset.getProcessingAttempts() + 1);
        videoAssetRepo.save(asset);

        Path jobDir = Path.of(workerProperties.getProcessing().getTempDir(), "jobs", jobId);

        try {
            // 2. DOWNLOADING
            updateJobStatus(job, "DOWNLOADING");
            Path inputFile = jobDir.resolve("input.mp4");
            Files.createDirectories(jobDir);
            storageService.download(payload.getStorageKey(), inputFile);
            asset.setFileSizeBytes(Files.size(inputFile));

            // 3. PROBING
            updateJobStatus(job, "PROBING");
            FFprobeRunner.VideoMetadata meta = ffprobeRunner.probe(inputFile.toString());
            if (meta.durationSeconds() > workerProperties.getProcessing().getMaxDurationSeconds()) {
                throw new FFprobeRunner.ValidationException(
                    "Video duration " + meta.durationSeconds() + "s exceeds max allowed "
                        + workerProperties.getProcessing().getMaxDurationSeconds() + "s");
            }
            applyMetadataToAsset(asset, meta);

            // 4. RESOLUTION LADDER PLANNING
            List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(meta.height());

            // 5. TRANSCODING
            updateJobStatus(job, "TRANSCODING");
            for (ResolutionLadder.Tier tier : tiers) {
                Path tierDir = jobDir.resolve(tier.label());
                Files.createDirectories(tierDir);
                ffmpegRunner.transcode(inputFile.toString(), tierDir.toString(),
                    tier.width(), tier.height(), tier.videoBitrate(), tier.audioBitrate());
            }

            // 6. THUMBNAIL GENERATION
            updateJobStatus(job, "THUMBNAIL_GENERATION");
            Path thumbnailPath = jobDir.resolve("thumbnail.jpg");
            ffmpegRunner.generateThumbnail(inputFile.toString(), thumbnailPath.toString());

            // 7. UPLOADING_OUTPUT
            updateJobStatus(job, "UPLOADING_OUTPUT");
            String assetPrefix = "processed/" + videoAssetId;
            for (ResolutionLadder.Tier tier : tiers) {
                storageService.uploadDirectory(
                    assetPrefix + "/" + tier.label(),
                    jobDir.resolve(tier.label())
                );
            }
            String masterKey = assetPrefix + "/master.m3u8";
            String masterContent = MasterPlaylistGenerator.generate(tiers);
            Path masterPath = jobDir.resolve("master.m3u8");
            Files.writeString(masterPath, masterContent);
            storageService.upload(masterKey, masterPath);

            String thumbnailKey = "thumbnails/" + videoAssetId + "/poster.jpg";
            storageService.upload(thumbnailKey, thumbnailPath);

            // 8. FINALIZING
            updateJobStatus(job, "FINALIZING");
            for (ResolutionLadder.Tier tier : tiers) {
                VideoVariant variant = buildVariant(videoAssetId, tier, assetPrefix);
                videoVariantRepo.save(variant);
            }
            asset.setManifestUrl(masterKey);
            asset.setProcessingStatus("READY");
            videoAssetRepo.save(asset);

            job.setStatus("DONE");
            job.setCompletedAt(Instant.now());
            processingJobRepo.save(job);

            // Notify downstream (best-effort; notification service built in Batch 12)
            rabbitTemplate.convertAndSend(
                com.tinniestudio.worker.config.RabbitConfig.EXCHANGE,
                "notifications.send",
                Map.of(
                    "type", "CONTENT_PROCESSED",
                    "videoAssetId", videoAssetId.toString(),
                    "contentId", asset.getContentId() != null ? asset.getContentId().toString() : "",
                    "status", "READY"
                )
            );

            log.info("VideoAsset {} processed successfully", videoAssetId);

        } catch (FFprobeRunner.ValidationException e) {
            log.error("Non-retryable validation failure for asset {}: {}", videoAssetId, e.getMessage());
            markFailed(asset, job, e.getMessage());
        } catch (Exception e) {
            log.error("Retryable error processing asset {}: {}", videoAssetId, e.getMessage());
            markFailed(asset, job, e.getMessage());
            throw new RetryableProcessingException(e.getMessage(), e);
        } finally {
            cleanup(jobDir);
        }
    }

    private ProcessingJob createJob(String jobId, UUID videoAssetId, int attempt) {
        ProcessingJob job = new ProcessingJob();
        job.setJobId(jobId);
        job.setVideoAssetId(videoAssetId);
        job.setStatus("VALIDATING");
        job.setAttempt(attempt);
        job.setStageStartedAt(Instant.now());
        return processingJobRepo.save(job);
    }

    private void updateJobStatus(ProcessingJob job, String status) {
        job.setStatus(status);
        job.setStageStartedAt(Instant.now());
        processingJobRepo.save(job);
    }

    private void applyMetadataToAsset(VideoAsset asset, FFprobeRunner.VideoMetadata meta) {
        asset.setDurationSeconds(meta.durationSeconds());
        asset.setWidth(meta.width());
        asset.setHeight(meta.height());
        asset.setCodec(meta.codec());
        asset.setBitrate(meta.bitrate());
    }

    private VideoVariant buildVariant(UUID videoAssetId, ResolutionLadder.Tier tier, String assetPrefix) {
        VideoVariant v = new VideoVariant();
        v.setVideoAssetId(videoAssetId);
        v.setResolution(tier.label());
        v.setWidth(tier.width());
        v.setHeight(tier.height());
        v.setBitrate(tier.bitrateValue());
        v.setManifestKey(assetPrefix + "/" + tier.label() + "/playlist.m3u8");
        return v;
    }

    private void markFailed(VideoAsset asset, ProcessingJob job, String error) {
        asset.setProcessingStatus("FAILED");
        asset.setProcessingError(error);
        videoAssetRepo.save(asset);

        job.setStatus("FAILED");
        job.setErrorMessage(error);
        job.setCompletedAt(Instant.now());
        processingJobRepo.save(job);
    }

    private void cleanup(Path jobDir) {
        try {
            if (Files.exists(jobDir)) {
                try (var walk = Files.walk(jobDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); }
                            catch (IOException ignored) {}
                        });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to clean up temp dir {}: {}", jobDir, e.getMessage());
        }
    }

    public static class RetryableProcessingException extends RuntimeException {
        public RetryableProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
