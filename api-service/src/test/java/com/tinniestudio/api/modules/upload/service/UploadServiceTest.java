package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.upload.config.UploadConfig;
import com.tinniestudio.api.modules.upload.dto.*;
import com.tinniestudio.api.modules.upload.repository.MediaFileRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.entity.MediaFile;
import com.tinniestudio.api.shared.entity.UploadSession;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.queue.QueuePublisher;
import com.tinniestudio.api.shared.storage.PresignedUploadResult;
import com.tinniestudio.api.shared.storage.StorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UploadService")
class UploadServiceTest {

    @Mock UploadSessionRepository uploadSessionRepository;
    @Mock MediaFileRepository mediaFileRepository;
    @Mock VideoAssetRepository videoAssetRepository;
    @Mock StorageService storageService;
    @Mock QueuePublisher queuePublisher;
    @Mock UploadConfig uploadConfig;

    @InjectMocks UploadService uploadService;

    private final UUID userId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    // ── helper ──────────────────────────────────────────────────────────────

    private UploadSession pendingSession(UploadType type, String key) {
        UploadSession s = new UploadSession();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setUploadType(type);
        s.setStorageKey(key);
        s.setUploadStatus(UploadStatus.PENDING);
        s.setExpiresAt(Instant.now().plusSeconds(300));
        return s;
    }

    // ── createSession ────────────────────────────────────────────────────────

    @Nested @DisplayName("createSession()")
    class CreateSessionTests {

        @Test @DisplayName("returns presigned URL response for valid RAW_VIDEO request")
        void createsSessionForRawVideo() {
            var req = new CreateUploadSessionRequest(
                UploadType.RAW_VIDEO, TargetEntityType.EPISODE, targetId,
                "video.mp4", "video/mp4", 1_000_000L);
            when(uploadConfig.isMimeTypeAllowed(UploadType.RAW_VIDEO, "video/mp4")).thenReturn(true);
            when(uploadConfig.getMaxBytes(UploadType.RAW_VIDEO)).thenReturn(10_737_418_240L);
            Instant expiry = Instant.now().plusSeconds(1800);
            when(storageService.generateUploadUrl(any(), eq("video/mp4"), anyLong(), any()))
                .thenReturn(new PresignedUploadResult("https://minio/upload", "raw/uuid/original.mp4", expiry));
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> {
                UploadSession s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            UploadSessionResponse result = uploadService.createSession(userId, req);

            assertThat(result.uploadUrl()).isEqualTo("https://minio/upload");
            assertThat(result.storageKey()).isEqualTo("raw/uuid/original.mp4");
            assertThat(result.expiresAt()).isEqualTo(expiry);
            assertThat(result.sessionId()).isNotNull();
        }

        @Test @DisplayName("throws 400 when MIME type not allowed")
        void throws400OnDisallowedMimeType() {
            var req = new CreateUploadSessionRequest(
                UploadType.THUMBNAIL, null, null, "video.mp4", "video/mp4", 1_000_000L);
            when(uploadConfig.isMimeTypeAllowed(UploadType.THUMBNAIL, "video/mp4")).thenReturn(false);

            assertThatThrownBy(() -> uploadService.createSession(userId, req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test @DisplayName("throws 400 when file size exceeds maximum")
        void throws400OnFileSizeExceeded() {
            var req = new CreateUploadSessionRequest(
                UploadType.THUMBNAIL, null, null, "large.jpg", "image/jpeg", 100_000_000L);
            when(uploadConfig.isMimeTypeAllowed(UploadType.THUMBNAIL, "image/jpeg")).thenReturn(true);
            when(uploadConfig.getMaxBytes(UploadType.THUMBNAIL)).thenReturn(10_485_760L);

            assertThatThrownBy(() -> uploadService.createSession(userId, req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ── completeSession ──────────────────────────────────────────────────────

    @Nested @DisplayName("completeSession()")
    class CompleteSessionTests {

        @Test @DisplayName("completes THUMBNAIL session without creating VideoAsset or publishing")
        void completesThumbnailSession() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "thumbnails/uuid/thumb.jpg");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("thumbnails/uuid/thumb.jpg")).thenReturn(true);
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = uploadService.completeSession(userId, session.getId());

            assertThat(result.mediaFileId()).isNotNull();
            assertThat(result.videoAssetId()).isNull();
            verify(queuePublisher, never()).publish(any(), any(), any());
        }

        @Test @DisplayName("completes RAW_VIDEO session, creates VideoAsset, publishes to queue")
        void completesRawVideoAndPublishesToQueue() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("raw/uuid/original.mp4")).thenReturn(true);
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            when(videoAssetRepository.save(any())).thenAnswer(inv -> {
                VideoAsset a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = uploadService.completeSession(userId, session.getId());

            assertThat(result.videoAssetId()).isNotNull();
            verify(queuePublisher).publish(eq("media.video.process"), eq("VIDEO_PROCESSING_JOB"), any());
        }

        @Test @DisplayName("throws 404 when session not found")
        void throws404WhenSessionNotFound() {
            UUID id = UUID.randomUUID();
            when(uploadSessionRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> uploadService.completeSession(userId, id))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test @DisplayName("throws 403 when session belongs to different user")
        void throws403WhenWrongUser() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "key");
            session.setUserId(UUID.randomUUID()); // different user
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test @DisplayName("throws 422 when session already completed")
        void throws422WhenAlreadyCompleted() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "key");
            session.setUploadStatus(UploadStatus.COMPLETED);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test @DisplayName("throws 422 when session is expired")
        void throws422WhenExpired() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "key");
            session.setExpiresAt(Instant.now().minusSeconds(60));
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test @DisplayName("throws 422 when object not found in storage")
        void throws422WhenObjectMissingFromStorage() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "thumbnails/uuid/thumb.jpg");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("thumbnails/uuid/thumb.jpg")).thenReturn(false);

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // ── getStatus ────────────────────────────────────────────────────────────

    @Nested @DisplayName("getStatus()")
    class GetStatusTests {

        @Test @DisplayName("returns session and null processingStatus for THUMBNAIL")
        void returnsStatusForThumbnailSession() {
            UploadSession session = new UploadSession();
            session.setId(UUID.randomUUID());
            session.setUserId(userId);
            session.setUploadType(UploadType.THUMBNAIL);
            session.setUploadStatus(UploadStatus.COMPLETED);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(videoAssetRepository.findByUploadSessionId(session.getId())).thenReturn(Optional.empty());

            UploadStatusResponse result = uploadService.getStatus(userId, session.getId());

            assertThat(result.uploadStatus()).isEqualTo("COMPLETED");
            assertThat(result.processingStatus()).isNull();
        }

        @Test @DisplayName("throws 403 when session belongs to different user")
        void throws403WhenWrongUser() {
            UploadSession session = new UploadSession();
            session.setId(UUID.randomUUID());
            session.setUserId(UUID.randomUUID());
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.getStatus(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
