package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.modules.upload.config.UploadConfig;
import com.tinniestudio.api.modules.upload.dto.*;
import com.tinniestudio.api.modules.upload.repository.MediaFileRepository;
import com.tinniestudio.api.modules.upload.repository.SubtitleRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.MediaFile;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.entity.Subtitle;
import com.tinniestudio.api.shared.entity.UploadSession;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.queue.QueuePublisher;
import com.tinniestudio.api.shared.storage.CompletedPartInfo;
import com.tinniestudio.api.shared.storage.MultipartUploadHandle;
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

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UploadService")
class UploadServiceTest {

    @Mock UploadSessionRepository uploadSessionRepository;
    @Mock MediaFileRepository mediaFileRepository;
    @Mock VideoAssetRepository videoAssetRepository;
    @Mock SubtitleRepository subtitleRepository;
    @Mock StorageService storageService;
    @Mock QueuePublisher queuePublisher;
    @Mock UploadConfig uploadConfig;
    @Mock AppProperties appProperties;
    @Mock ContentRepository contentRepository;
    @Mock SeasonRepository seasonRepository;
    @Mock EpisodeRepository episodeRepository;

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

        // NOTE: this test originally used RAW_VIDEO here, exercising the single-PUT flow. Task 8
        // (the RAW_VIDEO/TRAILER multipart branch below) made that premise impossible — RAW_VIDEO
        // now always goes through createMultipartSession(), never generateUploadUrl(). Switched to
        // PARTNER_LOGO so this test keeps covering the single-PUT response shape (sessionId,
        // storageKey, expiresAt) that the newer, narrower thumbnailStaysSinglePut() test doesn't
        // assert on. RAW_VIDEO's own (multipart) coverage now lives in
        // createsMultipartSessionForRawVideo() below.
        @Test @DisplayName("returns presigned URL response for valid PARTNER_LOGO request")
        void createsSessionForPartnerLogo() {
            var req = new CreateUploadSessionRequest(
                UploadType.PARTNER_LOGO, TargetEntityType.CONTENT, targetId,
                "logo.jpg", "image/jpeg", 1_000_000L);
            when(uploadConfig.isMimeTypeAllowed(UploadType.PARTNER_LOGO, "image/jpeg")).thenReturn(true);
            when(uploadConfig.getMaxBytes(UploadType.PARTNER_LOGO)).thenReturn(5_242_880L);
            Instant expiry = Instant.now().plusSeconds(1800);
            when(storageService.generateUploadUrl(any(), eq("image/jpeg"), anyLong(), any()))
                .thenReturn(new PresignedUploadResult("https://minio/upload", "partner-logos/uuid/logo.jpg", expiry));
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> {
                UploadSession s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            UploadSessionResponse result = uploadService.createSession(userId, req);

            assertThat(result.uploadUrl()).isEqualTo("https://minio/upload");
            assertThat(result.storageKey()).isEqualTo("partner-logos/uuid/logo.jpg");
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

        @Test @DisplayName("sanitizes a path-traversal extension instead of injecting it into the storage key")
        void sanitizesMaliciousExtension() {
            var req = new CreateUploadSessionRequest(
                UploadType.THUMBNAIL, TargetEntityType.CONTENT, targetId,
                "a.png/../../../evil-prefix/x", "image/png", 1_000L);
            when(uploadConfig.isMimeTypeAllowed(UploadType.THUMBNAIL, "image/png")).thenReturn(true);
            when(uploadConfig.getMaxBytes(UploadType.THUMBNAIL)).thenReturn(10_485_760L);
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            when(storageService.generateUploadUrl(keyCaptor.capture(), eq("image/png"), anyLong(), any()))
                .thenReturn(new PresignedUploadResult("https://minio/upload", "thumbnails/uuid/uuid.bin", Instant.now().plusSeconds(1800)));
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> {
                UploadSession s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            uploadService.createSession(userId, req);

            String generatedKey = keyCaptor.getValue();
            assertThat(generatedKey).doesNotContain("..");
            // exactly two path separators: "thumbnails/{targetId}/{uuid}.{ext}"
            assertThat(generatedKey.chars().filter(c -> c == '/').count()).isEqualTo(2);
        }

        @Test @DisplayName("falls back to 'bin' extension when the filename has no valid extension")
        void fallsBackToBinExtension() {
            var req = new CreateUploadSessionRequest(
                UploadType.THUMBNAIL, TargetEntityType.CONTENT, targetId,
                "no-extension-at-all", "image/png", 1_000L);
            when(uploadConfig.isMimeTypeAllowed(UploadType.THUMBNAIL, "image/png")).thenReturn(true);
            when(uploadConfig.getMaxBytes(UploadType.THUMBNAIL)).thenReturn(10_485_760L);
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            when(storageService.generateUploadUrl(keyCaptor.capture(), eq("image/png"), anyLong(), any()))
                .thenReturn(new PresignedUploadResult("https://minio/upload", "thumbnails/uuid/uuid.bin", Instant.now().plusSeconds(1800)));
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> {
                UploadSession s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            uploadService.createSession(userId, req);

            assertThat(keyCaptor.getValue()).endsWith(".bin");
        }

        @Test @DisplayName("RAW_VIDEO initiates a multipart upload instead of a single presigned PUT")
        void createsMultipartSessionForRawVideo() {
            var req = new CreateUploadSessionRequest(
                UploadType.RAW_VIDEO, TargetEntityType.CONTENT, targetId,
                "movie.mp4", "video/mp4", 100_000_000L); // ~100MB -> 4 parts at 25MB
            when(uploadConfig.isMimeTypeAllowed(UploadType.RAW_VIDEO, "video/mp4")).thenReturn(true);
            when(uploadConfig.getMaxBytes(UploadType.RAW_VIDEO)).thenReturn(10_737_418_240L);
            when(storageService.initiateMultipartUpload(any(), eq("video/mp4")))
                .thenReturn(new MultipartUploadHandle("s3-upload-id"));
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> {
                UploadSession s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            UploadSessionResponse result = uploadService.createSession(userId, req);

            assertThat(result.uploadUrl()).isNull();
            assertThat(result.uploadId()).isEqualTo("s3-upload-id");
            assertThat(result.partSizeBytes()).isEqualTo(UploadConfig.MULTIPART_PART_SIZE_BYTES);
            assertThat(result.totalParts()).isEqualTo(4);
            verify(storageService, never()).generateUploadUrl(any(), any(), anyLong(), any());
        }

        @Test @DisplayName("THUMBNAIL still uses the single-PUT flow, unaffected by the RAW_VIDEO/TRAILER multipart branch")
        void thumbnailStaysSinglePut() {
            var req = new CreateUploadSessionRequest(
                UploadType.THUMBNAIL, TargetEntityType.CONTENT, targetId,
                "poster.jpg", "image/jpeg", 500_000L);
            when(uploadConfig.isMimeTypeAllowed(UploadType.THUMBNAIL, "image/jpeg")).thenReturn(true);
            when(uploadConfig.getMaxBytes(UploadType.THUMBNAIL)).thenReturn(10_485_760L);
            when(storageService.generateUploadUrl(any(), eq("image/jpeg"), anyLong(), any()))
                .thenReturn(new PresignedUploadResult("https://minio/upload", "thumbnails/uuid/uuid.jpg", Instant.now().plusSeconds(1800)));
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> {
                UploadSession s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            UploadSessionResponse result = uploadService.createSession(userId, req);

            assertThat(result.uploadUrl()).isEqualTo("https://minio/upload");
            assertThat(result.uploadId()).isNull();
            verify(storageService, never()).initiateMultipartUpload(any(), any());
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

            var result = uploadService.completeSession(userId, session.getId(), null);

            assertThat(result.mediaFileId()).isNotNull();
            assertThat(result.videoAssetId()).isNull();
            verify(queuePublisher, never()).publish(any(), any(), any());
        }

        @Test @DisplayName("persists the storage-measured size, not the client-declared expectedMaxSizeBytes")
        void persistsStorageMeasuredSizeNotClientDeclaredSize() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "thumbnails/uuid/thumb.jpg");
            session.setExpectedMaxSizeBytes(999_999_999L); // client lied about the size up front
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("thumbnails/uuid/thumb.jpg")).thenReturn(true);
            when(storageService.getObjectSize("thumbnails/uuid/thumb.jpg")).thenReturn(4_096L); // actual size
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            ArgumentCaptor<UploadSession> captor = ArgumentCaptor.forClass(UploadSession.class);
            when(uploadSessionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            uploadService.completeSession(userId, session.getId(), null);

            assertThat(captor.getValue().getFileSizeBytes()).isEqualTo(4_096L);
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

            var result = uploadService.completeSession(userId, session.getId(), null);

            assertThat(result.videoAssetId()).isNotNull();
            verify(queuePublisher).publish(eq("media.video.process"), eq("VIDEO_PROCESSING_JOB"), any());
        }

        @Test @DisplayName("completes TRAILER session, sets VideoAssetType.TRAILER, publishes to queue")
        void completesTrailerAndPublishesToQueue() {
            UploadSession session = pendingSession(UploadType.TRAILER, "raw/trailers/uuid/original.mp4");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("raw/trailers/uuid/original.mp4")).thenReturn(true);
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

            var result = uploadService.completeSession(userId, session.getId(), null);

            assertThat(result.videoAssetId()).isNotNull();
            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepository).save(captor.capture());
            assertThat(captor.getValue().getAssetType()).isEqualTo(VideoAssetType.TRAILER);
            verify(queuePublisher).publish(eq("media.video.process"), eq("VIDEO_PROCESSING_JOB"), any());
        }

        @Test @DisplayName("throws 404 when session not found")
        void throws404WhenSessionNotFound() {
            UUID id = UUID.randomUUID();
            when(uploadSessionRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> uploadService.completeSession(userId, id, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test @DisplayName("throws 403 when session belongs to different user")
        void throws403WhenWrongUser() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "key");
            session.setUserId(UUID.randomUUID()); // different user
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test @DisplayName("throws 422 when session already completed")
        void throws422WhenAlreadyCompleted() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "key");
            session.setUploadStatus(UploadStatus.COMPLETED);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test @DisplayName("throws 422 when session is expired")
        void throws422WhenExpired() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "key");
            session.setExpiresAt(Instant.now().minusSeconds(60));
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test @DisplayName("throws 422 when object not found in storage")
        void throws422WhenObjectMissingFromStorage() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "thumbnails/uuid/thumb.jpg");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("thumbnails/uuid/thumb.jpg")).thenReturn(false);

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test @DisplayName("completes a multipart RAW_VIDEO session via storageService.completeMultipartUpload, not objectExists/getObjectSize")
        void completesMultipartSession() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            session.setMultipartUploadId("s3-upload-id");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
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
            when(storageService.getObjectSize("raw/uuid/original.mp4")).thenReturn(104_857_600L);

            var parts = java.util.List.of(new CompletedPartInfo(1, "etag-1"), new CompletedPartInfo(2, "etag-2"));
            var body = new CompleteUploadRequest(null, null, null, parts);

            var result = uploadService.completeSession(userId, session.getId(), body);

            verify(storageService).completeMultipartUpload("raw/uuid/original.mp4", "s3-upload-id", parts);
            verify(storageService, never()).objectExists(any());
            assertThat(result.videoAssetId()).isNotNull();
            verify(queuePublisher).publish(eq("media.video.process"), eq("VIDEO_PROCESSING_JOB"), any());
        }

        @Test @DisplayName("throws 400 when completing a multipart session without a parts list")
        void throws400WhenMultipartCompleteMissingParts() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            session.setMultipartUploadId("s3-upload-id");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        }

        // ── SUBTITLE completion: persists a Subtitle row (Batch 15 gap — was previously dead) ──

        @Test @DisplayName("completes SUBTITLE session by creating a Subtitle row attached to the target VideoAsset")
        void completesSubtitleSession_createsSubtitleRow() {
            UUID videoAssetId = UUID.randomUUID();
            UploadSession session = pendingSession(UploadType.SUBTITLE, "subtitles/" + videoAssetId + "/uuid.vtt");
            session.setTargetEntityId(videoAssetId);
            session.setMimeType("text/vtt");
            VideoAsset asset = new VideoAsset();
            asset.setId(videoAssetId);
            asset.setUploadedBy(userId); // caller owns the asset they're attaching a subtitle to

            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists(session.getStorageKey())).thenReturn(true);
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.of(asset));
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            AppProperties.Cdn cdn = new AppProperties.Cdn();
            cdn.setBaseUrl("https://cdn.test");
            when(appProperties.getCdn()).thenReturn(cdn);
            ArgumentCaptor<Subtitle> captor = ArgumentCaptor.forClass(Subtitle.class);
            when(subtitleRepository.save(captor.capture())).thenAnswer(inv -> {
                Subtitle s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            CompleteUploadRequest body = new CompleteUploadRequest("en", "English", true, null);
            var result = uploadService.completeSession(userId, session.getId(), body);

            assertThat(result.subtitleId()).isNotNull();
            assertThat(result.videoAssetId()).isNull(); // SUBTITLE never creates a new VideoAsset
            Subtitle saved = captor.getValue();
            assertThat(saved.getVideoAsset()).isSameAs(asset);
            assertThat(saved.getLanguageCode()).isEqualTo("en");
            assertThat(saved.getLabel()).isEqualTo("English");
            assertThat(saved.getIsDefault()).isTrue();
            assertThat(saved.getFormat()).isEqualTo(SubtitleFormat.VTT);
            assertThat(saved.getFileUrl()).isEqualTo("https://cdn.test/" + session.getStorageKey());
        }

        @Test @DisplayName("throws 400 when completing a SUBTITLE session without languageCode")
        void completesSubtitleSession_missingLanguageCode_throws400() {
            UUID videoAssetId = UUID.randomUUID();
            UploadSession session = pendingSession(UploadType.SUBTITLE, "subtitles/" + videoAssetId + "/uuid.vtt");
            session.setTargetEntityId(videoAssetId);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists(session.getStorageKey())).thenReturn(true);
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
            verify(subtitleRepository, never()).save(any());
        }

        @Test @DisplayName("throws 404 when completing a SUBTITLE session whose target VideoAsset doesn't exist")
        void completesSubtitleSession_missingVideoAsset_throws404() {
            UUID videoAssetId = UUID.randomUUID();
            UploadSession session = pendingSession(UploadType.SUBTITLE, "subtitles/" + videoAssetId + "/uuid.vtt");
            session.setTargetEntityId(videoAssetId);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists(session.getStorageKey())).thenReturn(true);
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.empty());
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() -> uploadService.completeSession(
                    userId, session.getId(), new CompleteUploadRequest("en", null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test @DisplayName("throws 404 (IDOR guard) when the caller doesn't own the target VideoAsset")
        void completesSubtitleSession_targetAssetOwnedByAnotherUser_throws404() {
            // UploadController has no PARTNER-role restriction — any authenticated user can call
            // it — so without this check any user could attach a subtitle to a video asset they
            // don't own just by supplying its UUID as targetEntityId.
            UUID videoAssetId = UUID.randomUUID();
            UploadSession session = pendingSession(UploadType.SUBTITLE, "subtitles/" + videoAssetId + "/uuid.vtt");
            session.setTargetEntityId(videoAssetId);
            VideoAsset assetOwnedBySomeoneElse = new VideoAsset();
            assetOwnedBySomeoneElse.setId(videoAssetId);
            assetOwnedBySomeoneElse.setUploadedBy(UUID.randomUUID()); // not `userId`

            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists(session.getStorageKey())).thenReturn(true);
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.of(assetOwnedBySomeoneElse));
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() -> uploadService.completeSession(
                    userId, session.getId(), new CompleteUploadRequest("en", null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
            verify(subtitleRepository, never()).save(any());
        }
    }

    // ── video-to-content/season/episode linking (completeSession) ─────────────

    @Nested @DisplayName("completeSession() — video linking")
    class VideoLinkingTests {

        private Content contentOwnedByCaller() {
            Content c = new Content();
            c.setId(targetId);
            c.setCreatedBy(userId);
            return c;
        }

        @Test @DisplayName("links VideoAsset.content when targetEntityType is CONTENT and caller owns it")
        void linksContentWhenOwned() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            session.setTargetEntityType(TargetEntityType.CONTENT);
            session.setTargetEntityId(targetId);
            Content content = contentOwnedByCaller();
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("raw/uuid/original.mp4")).thenReturn(true);
            when(contentRepository.findById(targetId)).thenReturn(Optional.of(content));
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            when(videoAssetRepository.save(captor.capture())).thenAnswer(inv -> {
                VideoAsset a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            uploadService.completeSession(userId, session.getId(), null);

            assertThat(captor.getValue().getContent()).isSameAs(content);
            assertThat(captor.getValue().getSeason()).isNull();
            assertThat(captor.getValue().getEpisode()).isNull();
        }

        @Test @DisplayName("throws 404 when targetEntityType is CONTENT but caller doesn't own it")
        void throws404WhenContentNotOwned() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            session.setTargetEntityType(TargetEntityType.CONTENT);
            session.setTargetEntityId(targetId);
            Content content = new Content();
            content.setId(targetId);
            content.setCreatedBy(UUID.randomUUID()); // different owner
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("raw/uuid/original.mp4")).thenReturn(true);
            when(contentRepository.findById(targetId)).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
            verify(videoAssetRepository, never()).save(any());
        }

        @Test @DisplayName("throws 404 when targetEntityType is CONTENT but the content doesn't exist")
        void throws404WhenContentMissing() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            session.setTargetEntityType(TargetEntityType.CONTENT);
            session.setTargetEntityId(targetId);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("raw/uuid/original.mp4")).thenReturn(true);
            when(contentRepository.findById(targetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test @DisplayName("links VideoAsset.season AND .content (denormalized) when targetEntityType is SEASON and caller owns the parent content")
        void linksSeasonAndDenormalizedContentWhenOwned() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            UUID seasonId = UUID.randomUUID();
            session.setTargetEntityType(TargetEntityType.SEASON);
            session.setTargetEntityId(seasonId);
            Content content = contentOwnedByCaller();
            Season season = new Season();
            season.setId(seasonId);
            season.setContent(content);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("raw/uuid/original.mp4")).thenReturn(true);
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            when(videoAssetRepository.save(captor.capture())).thenAnswer(inv -> {
                VideoAsset a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            uploadService.completeSession(userId, session.getId(), null);

            assertThat(captor.getValue().getSeason()).isSameAs(season);
            assertThat(captor.getValue().getContent()).isSameAs(content);
        }

        @Test @DisplayName("links VideoAsset.episode AND .content (denormalized, via episode->season->content) when targetEntityType is EPISODE and caller owns the parent content")
        void linksEpisodeAndDenormalizedContentWhenOwned() {
            UploadSession session = pendingSession(UploadType.TRAILER, "raw/trailers/uuid/original.mp4");
            UUID episodeId = UUID.randomUUID();
            session.setTargetEntityType(TargetEntityType.EPISODE);
            session.setTargetEntityId(episodeId);
            Content content = contentOwnedByCaller();
            Season season = new Season();
            season.setContent(content);
            Episode episode = new Episode();
            episode.setId(episodeId);
            episode.setSeason(season);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("raw/trailers/uuid/original.mp4")).thenReturn(true);
            when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            when(videoAssetRepository.save(captor.capture())).thenAnswer(inv -> {
                VideoAsset a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            uploadService.completeSession(userId, session.getId(), null);

            assertThat(captor.getValue().getEpisode()).isSameAs(episode);
            assertThat(captor.getValue().getContent()).isSameAs(content);
        }

        @Test @DisplayName("throws 404 when targetEntityType is EPISODE but caller doesn't own the parent content")
        void throws404WhenEpisodeParentNotOwned() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            UUID episodeId = UUID.randomUUID();
            session.setTargetEntityType(TargetEntityType.EPISODE);
            session.setTargetEntityId(episodeId);
            Content content = new Content();
            content.setCreatedBy(UUID.randomUUID()); // different owner
            Season season = new Season();
            season.setContent(content);
            Episode episode = new Episode();
            episode.setId(episodeId);
            episode.setSeason(season);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("raw/uuid/original.mp4")).thenReturn(true);
            when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
            verify(videoAssetRepository, never()).save(any());
        }

        @Test @DisplayName("does not attempt any link when targetEntityType is null (existing untargeted-upload behavior preserved)")
        void noLinkWhenTargetEntityTypeNull() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            // targetEntityType left null, matching the two pre-existing tests in CompleteSessionTests
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("raw/uuid/original.mp4")).thenReturn(true);
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            when(videoAssetRepository.save(captor.capture())).thenAnswer(inv -> {
                VideoAsset a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            uploadService.completeSession(userId, session.getId(), null);

            assertThat(captor.getValue().getContent()).isNull();
            verifyNoInteractions(contentRepository, seasonRepository, episodeRepository);
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

    // ── multipart part-URL / list-parts / active-session lookup ───────────────

    @Nested @DisplayName("multipart helpers")
    class MultipartHelperTests {

        @Test @DisplayName("getPartUploadUrl returns a freshly-signed URL for a PENDING multipart session owned by the caller")
        void returnsPartUploadUrl() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            session.setMultipartUploadId("s3-upload-id");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.generatePartUploadUrl("raw/uuid/original.mp4", "s3-upload-id", 2, UploadConfig.PRESIGNED_URL_TTL))
                .thenReturn("https://minio/part-2");

            PartUploadUrlResponse result = uploadService.getPartUploadUrl(userId, session.getId(), 2);

            assertThat(result.url()).isEqualTo("https://minio/part-2");
        }

        @Test @DisplayName("getPartUploadUrl throws 403 when the session belongs to a different user")
        void getPartUploadUrlThrows403WhenWrongUser() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            session.setMultipartUploadId("s3-upload-id");
            session.setUserId(UUID.randomUUID());
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.getPartUploadUrl(userId, session.getId(), 1))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test @DisplayName("getPartUploadUrl throws 422 when the session isn't multipart")
        void getPartUploadUrlThrows422WhenNotMultipart() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "thumbnails/uuid/thumb.jpg");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.getPartUploadUrl(userId, session.getId(), 1))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test @DisplayName("listUploadedParts maps storage's part list to UploadedPartResponse for the owning caller")
        void listsUploadedParts() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            session.setMultipartUploadId("s3-upload-id");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.listUploadedParts("raw/uuid/original.mp4", "s3-upload-id"))
                .thenReturn(java.util.List.of(new com.tinniestudio.api.shared.storage.UploadedPart(1, "etag-1", 1000L)));

            var result = uploadService.listUploadedParts(userId, session.getId());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).partNumber()).isEqualTo(1);
            assertThat(result.get(0).eTag()).isEqualTo("etag-1");
        }

        @Test @DisplayName("listUploadedParts throws 403 when the session belongs to a different user")
        void listUploadedPartsThrows403WhenWrongUser() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            session.setMultipartUploadId("s3-upload-id");
            session.setUserId(UUID.randomUUID());
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.listUploadedParts(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test @DisplayName("listUploadedParts throws 422 when the session isn't multipart")
        void listUploadedPartsThrows422WhenNotMultipart() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "thumbnails/uuid/thumb.jpg");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.listUploadedParts(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test @DisplayName("findActiveSession returns the most recent matching PENDING session, mapped to UploadSessionResponse")
        void findsActiveSession() {
            UploadSession session = pendingSession(UploadType.RAW_VIDEO, "raw/uuid/original.mp4");
            session.setTargetEntityType(TargetEntityType.CONTENT);
            session.setTargetEntityId(targetId);
            session.setMultipartUploadId("s3-upload-id");
            session.setPartSizeBytes(UploadConfig.MULTIPART_PART_SIZE_BYTES);
            session.setExpectedMaxSizeBytes(100_000_000L);
            when(uploadSessionRepository.findActiveSessions(
                    eq(userId), eq(TargetEntityType.CONTENT), eq(targetId), eq(UploadType.RAW_VIDEO), any()))
                .thenReturn(java.util.List.of(session));

            var result = uploadService.findActiveSession(userId, TargetEntityType.CONTENT, targetId, UploadType.RAW_VIDEO);

            assertThat(result).isPresent();
            assertThat(result.get().sessionId()).isEqualTo(session.getId());
            assertThat(result.get().uploadId()).isEqualTo("s3-upload-id");
        }

        @Test @DisplayName("findActiveSession returns empty when none exists")
        void findsNoActiveSession() {
            when(uploadSessionRepository.findActiveSessions(any(), any(), any(), any(), any()))
                .thenReturn(java.util.List.of());

            var result = uploadService.findActiveSession(userId, TargetEntityType.CONTENT, targetId, UploadType.RAW_VIDEO);

            assertThat(result).isEmpty();
        }
    }
}
