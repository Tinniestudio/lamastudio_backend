package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.modules.upload.config.UploadConfig;
import com.tinniestudio.api.modules.upload.dto.*;
import com.tinniestudio.api.modules.upload.dto.PartUploadUrlResponse;
import com.tinniestudio.api.modules.upload.dto.UploadedPartResponse;
import com.tinniestudio.api.modules.upload.repository.MediaFileRepository;
import com.tinniestudio.api.modules.upload.repository.SubtitleRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.MediaFile;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.entity.Subtitle;
import com.tinniestudio.api.shared.entity.UploadSession;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.queue.QueuePublisher;
import com.tinniestudio.api.shared.storage.MultipartUploadHandle;
import com.tinniestudio.api.shared.storage.PresignedUploadResult;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {

    private final UploadSessionRepository uploadSessionRepository;
    private final MediaFileRepository mediaFileRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final SubtitleRepository subtitleRepository;
    private final StorageService storageService;
    private final QueuePublisher queuePublisher;
    private final UploadConfig uploadConfig;
    private final AppProperties appProperties;
    private final ContentRepository contentRepository;
    private final SeasonRepository seasonRepository;
    private final EpisodeRepository episodeRepository;

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

        UploadSession session = new UploadSession();
        session.setUserId(userId);
        session.setUploadType(req.uploadType());
        session.setTargetEntityType(req.targetEntityType());
        session.setTargetEntityId(req.targetEntityId());
        session.setOriginalFilename(req.originalFilename());
        session.setMimeType(req.mimeType());
        session.setExpectedMaxSizeBytes(req.fileSizeBytes());
        session.setUploadStatus(UploadStatus.PENDING);

        boolean isMultipart = req.uploadType() == UploadType.RAW_VIDEO || req.uploadType() == UploadType.TRAILER;
        if (isMultipart) {
            return createMultipartSession(session, storageKey, req);
        }
        return createSinglePutSession(session, storageKey, req, maxBytes);
    }

    private UploadSessionResponse createMultipartSession(UploadSession session, String storageKey, CreateUploadSessionRequest req) {
        MultipartUploadHandle handle = storageService.initiateMultipartUpload(storageKey, req.mimeType());
        session.setStorageKey(storageKey);
        session.setMultipartUploadId(handle.uploadId());
        session.setPartSizeBytes(UploadConfig.MULTIPART_PART_SIZE_BYTES);
        session.setExpiresAt(Instant.now().plus(UploadConfig.MULTIPART_SESSION_TTL));

        UploadSession saved = uploadSessionRepository.save(session);
        int totalParts = (int) Math.ceil(req.fileSizeBytes() / (double) UploadConfig.MULTIPART_PART_SIZE_BYTES);
        return new UploadSessionResponse(saved.getId(), null, storageKey, saved.getExpiresAt(),
            handle.uploadId(), UploadConfig.MULTIPART_PART_SIZE_BYTES, totalParts,
            req.originalFilename(), req.fileSizeBytes());
    }

    private UploadSessionResponse createSinglePutSession(UploadSession session, String storageKey, CreateUploadSessionRequest req, long maxBytes) {
        PresignedUploadResult presigned = storageService.generateUploadUrl(
            storageKey, req.mimeType(), maxBytes, UploadConfig.PRESIGNED_URL_TTL);
        // Use the confirmed storage key returned by the storage service (may differ from the hint)
        String confirmedKey = presigned.storageKey();
        session.setStorageKey(confirmedKey);
        session.setPresignedUrl(presigned.uploadUrl());
        session.setExpiresAt(presigned.expiresAt());

        UploadSession saved = uploadSessionRepository.save(session);
        return new UploadSessionResponse(saved.getId(), presigned.uploadUrl(), confirmedKey, presigned.expiresAt(),
            null, null, null, req.originalFilename(), req.fileSizeBytes());
    }

    @Transactional
    public CompleteUploadResponse completeSession(UUID userId, UUID sessionId, CompleteUploadRequest body) {
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
        long actualSizeBytes;
        if (session.getMultipartUploadId() != null) {
            if (body == null || body.parts() == null || body.parts().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "parts is required to complete a multipart upload session");
            }
            try {
                storageService.completeMultipartUpload(session.getStorageKey(), session.getMultipartUploadId(), body.parts());
            } catch (RuntimeException ex) {
                // Nothing committed to S3 in this branch (or the attempt itself is what failed) —
                // safe to tell the caller to retry, completeMultipartUpload() hasn't invalidated
                // the multipart uploadId here.
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Storage check failed — please retry", ex);
            }
            // completeMultipartUpload() succeeded past this point — S3 has assembled the object
            // and invalidated the multipart uploadId, so retrying this whole method from the top
            // would fail forever (NoSuchUpload). A failure past here must never surface as a
            // "please retry" 503 — degrade to a best-effort size and let completion proceed
            // instead, since actualSizeBytes only feeds session.fileSizeBytes (storage
            // accounting), not anything correctness-critical to VideoAsset creation/processing.
            try {
                actualSizeBytes = storageService.getObjectSize(session.getStorageKey());
            } catch (RuntimeException ex) {
                log.warn("getObjectSize failed after completeMultipartUpload succeeded for key={} — " +
                    "proceeding with fileSizeBytes=0, needs reconciliation", session.getStorageKey(), ex);
                actualSizeBytes = 0L;
            }
        } else {
            boolean objectPresent;
            try {
                objectPresent = storageService.objectExists(session.getStorageKey());
                // Measured from storage (a HEAD request), not the client-declared expectedMaxSizeBytes
                // from createSession — a caller can declare any size at presign time, so that value
                // must never be trusted for quota/storage-accounting figures.
                actualSizeBytes = objectPresent ? storageService.getObjectSize(session.getStorageKey()) : 0L;
            } catch (RuntimeException ex) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Storage check failed — please retry", ex);
            }
            if (!objectPresent) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Object not found in storage. Upload the file before calling complete.");
            }
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
        session.setFileSizeBytes(actualSizeBytes);
        session.setCompletedAt(Instant.now());
        uploadSessionRepository.save(session);

        UUID videoAssetId = null;
        UUID subtitleId = null;
        UploadType type = session.getUploadType();
        if (type == UploadType.RAW_VIDEO || type == UploadType.TRAILER) {
            VideoAsset asset = new VideoAsset();
            asset.setUploadSessionId(sessionId);
            asset.setUploadedBy(userId);
            asset.setAssetType(type == UploadType.TRAILER ? VideoAssetType.TRAILER : VideoAssetType.MAIN_VIDEO);
            asset.setStorageKey(session.getStorageKey());
            asset.setOriginalFilename(session.getOriginalFilename() != null ? session.getOriginalFilename() : "upload");
            asset.setProcessingStatus(ProcessingStatus.PENDING);
            linkTargetAndAssertOwnership(asset, session, userId);
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
        } else if (type == UploadType.SUBTITLE) {
            subtitleId = attachSubtitle(userId, session, body);
        }

        return new CompleteUploadResponse(savedFile.getId(), videoAssetId, subtitleId);
    }

    /**
     * Attaches a completed SUBTITLE upload to the VideoAsset named by
     * session.getTargetEntityId() — a Subtitle row can't be created at createSession() time since
     * the file doesn't exist in storage yet, and language/label aren't known until the client
     * supplies them here.
     */
    private UUID attachSubtitle(UUID userId, UploadSession session, CompleteUploadRequest body) {
        if (session.getTargetEntityId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "targetEntityId (the VideoAsset to attach this subtitle to) is required for SUBTITLE uploads");
        }
        if (body == null || body.languageCode() == null || body.languageCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "languageCode is required to complete a SUBTITLE upload");
        }
        VideoAsset asset = videoAssetRepository.findById(session.getTargetEntityId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "VideoAsset not found: " + session.getTargetEntityId()));
        // IDOR guard: UploadController has no PARTNER-role restriction (any authenticated user
        // can call it), so without this check any user could attach a subtitle to a video asset
        // they don't own just by guessing/enumerating its UUID. 404, not 403 — enumeration-safe,
        // matching the ownership-check pattern used elsewhere (AdminContentController,
        // PartnerServiceImpl.assertOwnedByPartner).
        if (!userId.equals(asset.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "VideoAsset not found: " + session.getTargetEntityId());
        }

        Subtitle subtitle = new Subtitle();
        subtitle.setVideoAsset(asset);
        subtitle.setLanguageCode(body.languageCode());
        subtitle.setLabel(body.label());
        subtitle.setFileUrl(appProperties.getCdn().getBaseUrl() + "/" + session.getStorageKey());
        subtitle.setFormat("text/vtt".equals(session.getMimeType()) ? SubtitleFormat.VTT : SubtitleFormat.SRT);
        subtitle.setIsDefault(Boolean.TRUE.equals(body.isDefault()));
        return subtitleRepository.save(subtitle).getId();
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

    @Transactional(readOnly = true)
    public PartUploadUrlResponse getPartUploadUrl(UUID userId, UUID sessionId, int partNumber) {
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
        if (session.getMultipartUploadId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Upload session is not a multipart upload");
        }
        String url = storageService.generatePartUploadUrl(
            session.getStorageKey(), session.getMultipartUploadId(), partNumber, UploadConfig.PRESIGNED_URL_TTL);
        return new PartUploadUrlResponse(url);
    }

    @Transactional(readOnly = true)
    public List<UploadedPartResponse> listUploadedParts(UUID userId, UUID sessionId) {
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
        if (session.getMultipartUploadId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Upload session is not a multipart upload");
        }
        return storageService.listUploadedParts(session.getStorageKey(), session.getMultipartUploadId())
            .stream()
            .map(p -> new UploadedPartResponse(p.partNumber(), p.eTag()))
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<UploadSessionResponse> findActiveSession(
            UUID userId, TargetEntityType targetEntityType, UUID targetEntityId, UploadType uploadType) {
        return uploadSessionRepository
            .findActiveSessions(userId, targetEntityType, targetEntityId, uploadType, Instant.now())
            .stream()
            .findFirst()
            .map(s -> {
                Integer totalParts = (s.getPartSizeBytes() != null && s.getExpectedMaxSizeBytes() != null)
                    ? (int) Math.ceil(s.getExpectedMaxSizeBytes() / (double) s.getPartSizeBytes())
                    : null;
                return new UploadSessionResponse(s.getId(), null, s.getStorageKey(), s.getExpiresAt(),
                    s.getMultipartUploadId(), s.getPartSizeBytes(), totalParts,
                    s.getOriginalFilename(), s.getExpectedMaxSizeBytes());
            });
    }

    private String buildStorageKey(UploadType type, UUID targetEntityId, String ext) {
        String uuid = UUID.randomUUID().toString();
        return switch (type) {
            case RAW_VIDEO -> "raw/" + uuid + "/original." + ext;
            case TRAILER   -> "raw/trailers/" + uuid + "/original." + ext;
            case THUMBNAIL -> "thumbnails/" + (targetEntityId != null ? targetEntityId : uuid) + "/" + uuid + "." + ext;
            case SUBTITLE  -> "subtitles/" + (targetEntityId != null ? targetEntityId : uuid) + "/" + uuid + "." + ext;
            case PARTNER_LOGO -> "partner-logos/" + (targetEntityId != null ? targetEntityId : uuid) + "/logo." + ext;
            default        -> "uploads/" + uuid + "." + ext;
        };
    }

    // Allow-list only plain alphanumeric extensions (max 10 chars) — anything else (path
    // separators, "..", empty) falls back to "bin" rather than being written into the storage
    // key. Mirrors the guard StringUtils.getFilenameExtension() gives PartnerServiceImpl for
    // logo uploads, applied here as well since this key ends up in a client-facing presigned URL.
    private static final java.util.regex.Pattern SAFE_EXTENSION = java.util.regex.Pattern.compile("^[a-zA-Z0-9]{1,10}$");

    private String extractExtension(String filename) {
        String ext = org.springframework.util.StringUtils.getFilenameExtension(filename);
        if (ext == null || !SAFE_EXTENSION.matcher(ext).matches()) return "bin";
        return ext.toLowerCase();
    }

    /**
     * Links a newly-created RAW_VIDEO/TRAILER VideoAsset back to the Content/Season/Episode it
     * was uploaded for (session.targetEntityType/targetEntityId, captured at createSession() time
     * but never read again until now), and verifies the caller owns that Content before doing so
     * — without this check, any authenticated user could scope an upload session to someone
     * else's content and silently attach a video to it (UploadController has no role
     * restriction). `content` is always set regardless of how specifically the upload was
     * targeted (even for SEASON/EPISODE) — media-worker's own VideoAsset entity only ever reads a
     * flat contentId column, no season/episode awareness, so this denormalization is what lets
     * the CONTENT_PROCESSED notification (and any other content-level lookup) work correctly for
     * episode-scoped videos too, without touching media-worker at all.
     */
    private void linkTargetAndAssertOwnership(VideoAsset asset, UploadSession session, UUID userId) {
        TargetEntityType type = session.getTargetEntityType();
        if (type == null || session.getTargetEntityId() == null) {
            return;
        }
        switch (type) {
            case CONTENT -> {
                Content content = contentRepository.findById(session.getTargetEntityId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Content not found: " + session.getTargetEntityId()));
                assertOwnsContent(userId, content);
                asset.setContent(content);
            }
            case SEASON -> {
                Season season = seasonRepository.findById(session.getTargetEntityId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Season not found: " + session.getTargetEntityId()));
                assertOwnsContent(userId, season.getContent());
                asset.setSeason(season);
                asset.setContent(season.getContent());
            }
            case EPISODE -> {
                Episode episode = episodeRepository.findById(session.getTargetEntityId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Episode not found: " + session.getTargetEntityId()));
                Content parentContent = episode.getSeason().getContent();
                assertOwnsContent(userId, parentContent);
                asset.setEpisode(episode);
                asset.setContent(parentContent);
            }
            case VIDEO_ASSET -> {
                // Not a valid target for RAW_VIDEO/TRAILER (VIDEO_ASSET only means anything for
                // SUBTITLE, handled entirely by attachSubtitle()) — no-op, matches prior behavior.
            }
        }
    }

    // 404, not 403 — enumeration-safe, same pattern as attachSubtitle()'s existing IDOR guard
    // just above. No admin bypass: this method only ever receives a raw UUID, not a
    // principal/role set, so it can't distinguish an admin from a partner — the same constraint
    // attachSubtitle() already lives with.
    private void assertOwnsContent(UUID userId, Content content) {
        if (!userId.equals(content.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + content.getId());
        }
    }
}
