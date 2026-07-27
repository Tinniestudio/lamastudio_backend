package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.upload.config.UploadConfig;
import com.tinniestudio.api.modules.upload.dto.*;
import com.tinniestudio.api.modules.upload.repository.MediaFileRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.MediaFile;
import com.tinniestudio.api.shared.entity.UploadSession;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.queue.QueuePublisher;
import com.tinniestudio.api.shared.storage.PresignedUploadResult;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UploadService {

    private final UploadSessionRepository uploadSessionRepository;
    private final MediaFileRepository mediaFileRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final StorageService storageService;
    private final QueuePublisher queuePublisher;
    private final UploadConfig uploadConfig;

    @Transactional
    public UploadSessionResponse createSession(UUID userId, CreateUploadSessionRequest req) {
        if (req.uploadType() == UploadType.VIDEO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "UploadType VIDEO is not supported; use RAW_VIDEO for video uploads");
        }
        if (!uploadConfig.isMimeTypeAllowed(req.uploadType(), req.mimeType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "MIME type " + req.mimeType() + " is not allowed for upload type " + req.uploadType());
        }
        long maxBytes = uploadConfig.getMaxBytes(req.uploadType());
        if (req.fileSizeBytes() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "File size " + req.fileSizeBytes() + " bytes exceeds maximum " + maxBytes + " for " + req.uploadType());
        }

        String ext = extractExtension(req.originalFilename());
        String storageKey = buildStorageKey(req.uploadType(), req.targetEntityId(), ext);

        PresignedUploadResult presigned = storageService.generateUploadUrl(
            storageKey, req.mimeType(), maxBytes, UploadConfig.PRESIGNED_URL_TTL);

        // Use the confirmed storage key returned by the storage service (may differ from the hint)
        String confirmedKey = presigned.storageKey();

        UploadSession session = new UploadSession();
        session.setUserId(userId);
        session.setUploadType(req.uploadType());
        session.setTargetEntityType(req.targetEntityType());
        session.setTargetEntityId(req.targetEntityId());
        session.setStorageKey(confirmedKey);
        session.setOriginalFilename(req.originalFilename());
        session.setMimeType(req.mimeType());
        session.setExpectedMaxSizeBytes(req.fileSizeBytes());
        session.setUploadStatus(UploadStatus.PENDING);
        session.setPresignedUrl(presigned.uploadUrl());
        session.setExpiresAt(presigned.expiresAt());

        UploadSession saved = uploadSessionRepository.save(session);
        return new UploadSessionResponse(saved.getId(), presigned.uploadUrl(), confirmedKey, presigned.expiresAt());
    }

    @Transactional
    public CompleteUploadResponse completeSession(UUID userId, UUID sessionId) {
        UploadSession session = uploadSessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Upload session not found: " + sessionId));

        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Upload session does not belong to this user");
        }
        if (session.getUploadStatus() != UploadStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Upload session is not in PENDING state (current: " + session.getUploadStatus() + ")");
        }
        if (Instant.now().isAfter(session.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Upload session has expired");
        }
        boolean objectPresent;
        try {
            objectPresent = storageService.objectExists(session.getStorageKey());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Storage check failed — please retry", ex);
        }
        if (!objectPresent) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Object not found in storage. Upload the file before calling complete.");
        }

        MediaFile mediaFile = new MediaFile();
        mediaFile.setUserId(userId);
        mediaFile.setUploadSessionId(sessionId);
        mediaFile.setFileType(session.getUploadType().name());
        mediaFile.setStorageKey(session.getStorageKey());
        mediaFile.setOriginalFilename(session.getOriginalFilename());
        mediaFile.setMimeType(session.getMimeType());
        MediaFile savedFile = mediaFileRepository.save(mediaFile);

        session.setUploadStatus(UploadStatus.COMPLETED);
        session.setFileSizeBytes(session.getExpectedMaxSizeBytes() != null
            ? session.getExpectedMaxSizeBytes() : 0L);
        session.setCompletedAt(Instant.now());
        uploadSessionRepository.save(session);

        UUID videoAssetId = null;
        UploadType type = session.getUploadType();
        if (type == UploadType.RAW_VIDEO || type == UploadType.TRAILER) {
            VideoAsset asset = new VideoAsset();
            asset.setUploadSessionId(sessionId);
            asset.setUploadedBy(userId);
            asset.setAssetType(type == UploadType.TRAILER ? VideoAssetType.TRAILER : VideoAssetType.MAIN_VIDEO);
            asset.setStorageKey(session.getStorageKey());
            asset.setOriginalFilename(session.getOriginalFilename() != null ? session.getOriginalFilename() : "upload");
            asset.setProcessingStatus(ProcessingStatus.PENDING);
            VideoAsset savedAsset = videoAssetRepository.save(asset);
            videoAssetId = savedAsset.getId();

            MediaProcessingJobPayload payload = new MediaProcessingJobPayload(
                UUID.randomUUID().toString(), savedAsset.getId(),
                session.getStorageKey(), sessionId);
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            queuePublisher.publish(UploadConfig.VIDEO_PROCESS_QUEUE, "VIDEO_PROCESSING_JOB", payload);
                        }
                    });
            } else {
                queuePublisher.publish(UploadConfig.VIDEO_PROCESS_QUEUE, "VIDEO_PROCESSING_JOB", payload);
            }
        }

        return new CompleteUploadResponse(savedFile.getId(), videoAssetId);
    }

    @Transactional(readOnly = true)
    public UploadStatusResponse getStatus(UUID userId, UUID sessionId) {
        UploadSession session = uploadSessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Upload session not found: " + sessionId));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Upload session does not belong to this user");
        }
        String processingStatus = videoAssetRepository.findByUploadSessionId(sessionId)
            .map(a -> a.getProcessingStatus().name())
            .orElse(null);
        return new UploadStatusResponse(sessionId, session.getUploadStatus().name(), processingStatus);
    }

    private String buildStorageKey(UploadType type, UUID targetEntityId, String ext) {
        String uuid = UUID.randomUUID().toString();
        return switch (type) {
            case RAW_VIDEO -> "raw/" + uuid + "/original." + ext;
            case TRAILER   -> "raw/trailers/" + uuid + "/original." + ext;
            case THUMBNAIL -> "thumbnails/" + (targetEntityId != null ? targetEntityId : uuid) + "/" + uuid + "." + ext;
            case SUBTITLE  -> "subtitles/" + (targetEntityId != null ? targetEntityId : uuid) + "/" + uuid + "." + ext;
            default        -> "uploads/" + uuid + "." + ext;
        };
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "bin";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
