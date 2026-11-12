package com.tinniestudio.api.modules.notification.consumer;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.notification.service.NotificationService;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.modules.upload.service.VideoActivationService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    @Mock NotificationService notificationService;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock ContentRepository contentRepo;
    @Mock VideoActivationService videoActivationService;
    @Mock UserRepository userRepository;
    @Mock CacheService cacheService;
    @InjectMocks NotificationConsumer consumer;

    @Test
    void handleContentProcessed_updatesStatusAndNotifies() {
        UUID assetId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();

        VideoAsset asset = new VideoAsset();
        asset.setProcessingStatus(ProcessingStatus.PROCESSING);

        Content content = new Content();
        content.setCreatedBy(creatorId);

        when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
        when(contentRepo.findById(contentId)).thenReturn(Optional.of(content));
        when(videoAssetRepo.save(any())).thenReturn(asset);

        consumer.handleNotificationEvent(Map.of(
            "type", "CONTENT_PROCESSED",
            "videoAssetId", assetId.toString(),
            "contentId", contentId.toString(),
            "status", "READY"
        ));

        verify(videoAssetRepo).save(asset);
        assertThat(asset.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        // CONTENT + contentId, not VIDEO_ASSET + assetId — the frontend has a content page to
        // deep-link to, not a video-asset detail view (B3 fix).
        verify(notificationService).sendNotification(
            eq(creatorId), eq(NotificationEventType.CONTENT_PROCESSED),
            eq("CONTENT"), eq(contentId));
    }

    @Test
    void handleContentProcessed_marksFailedWhenStatusIsNotReady() {
        UUID assetId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();

        VideoAsset asset = new VideoAsset();
        asset.setProcessingStatus(ProcessingStatus.PROCESSING);

        Content content = new Content();
        content.setCreatedBy(creatorId);

        when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
        when(contentRepo.findById(contentId)).thenReturn(Optional.of(content));
        when(videoAssetRepo.save(any())).thenReturn(asset);

        consumer.handleNotificationEvent(Map.of(
            "type", "CONTENT_PROCESSED",
            "videoAssetId", assetId.toString(),
            "contentId", contentId.toString(),
            "status", "FAILED"
        ));

        assertThat(asset.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
    }

    @Test
    void handleEvent_skips_whenTypeIsUnknown() {
        consumer.handleNotificationEvent(Map.of("type", "UNKNOWN_EVENT"));
        verifyNoInteractions(videoAssetRepo, contentRepo, notificationService);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("still updates the asset's processing status even when contentId can't be resolved (no notification sent)")
    void handleContentProcessed_updatesStatusButSkipsNotification_whenContentNotFound() {
        // Reproduces today's real-world path: VideoAsset.contentId is never populated by the
        // upload-completion flow (see UploadService.completeSession), so media-worker's publisher
        // sends an empty-string contentId, which fails UUID.fromString and is caught by the outer
        // try/catch — before that, though, a *resolvable* but nonexistent contentId hits this same
        // early return. Either way: the VideoAsset status update must not depend on notification
        // delivery succeeding.
        UUID assetId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        VideoAsset asset = new VideoAsset();
        asset.setProcessingStatus(ProcessingStatus.PROCESSING);

        when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
        when(contentRepo.findById(contentId)).thenReturn(Optional.empty());
        when(videoAssetRepo.save(any())).thenReturn(asset);

        consumer.handleNotificationEvent(Map.of(
            "type", "CONTENT_PROCESSED",
            "videoAssetId", assetId.toString(),
            "contentId", contentId.toString(),
            "status", "READY"
        ));

        assertThat(asset.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        verify(videoAssetRepo).save(asset);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("on successful processing, activates the processed asset and deactivates its siblings")
    void activatesProcessedAssetAndDeactivatesSiblings() {
        UUID assetId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        VideoAsset asset = new VideoAsset();
        asset.setId(assetId);
        asset.setAssetType(VideoAssetType.MAIN_VIDEO);
        Content content = new Content();
        content.setId(contentId);
        content.setCreatedBy(UUID.randomUUID());
        // The Task-1 (B6) video-linking fix populates this at upload time, well before the
        // CONTENT_PROCESSED message is consumed — mirror that here rather than leaving the
        // asset unlinked, since an unlinked asset is exactly the case the consumer skips.
        asset.setContent(content);

        Map<String, Object> message = Map.of(
            "type", "CONTENT_PROCESSED",
            "videoAssetId", assetId.toString(),
            "contentId", contentId.toString(),
            "status", "READY"
        );
        when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
        when(contentRepo.findById(contentId)).thenReturn(Optional.of(content));

        consumer.handleNotificationEvent(message);

        // The retire-siblings + activate sequence is delegated to VideoActivationService (its
        // own transactional atomicity is covered by VideoActivationServiceTest) — this test only
        // needs to confirm the consumer hands off the right asset and doesn't also save directly,
        // which would be a redundant/duplicate write of the same entity.
        verify(videoActivationService).activateAndRetireSiblings(asset);
        assertThat(asset.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        verify(videoAssetRepo, never()).save(any());
    }

    @Test
    @DisplayName("on failed processing, does not activate or retire siblings")
    void doesNotActivateOnFailedProcessing() {
        UUID assetId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        VideoAsset asset = new VideoAsset();
        asset.setId(assetId);
        asset.setAssetType(VideoAssetType.MAIN_VIDEO);

        Map<String, Object> message = Map.of(
            "type", "CONTENT_PROCESSED",
            "videoAssetId", assetId.toString(),
            "contentId", contentId.toString(),
            "status", "FAILED"
        );
        when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
        when(videoAssetRepo.save(any())).thenReturn(asset);

        consumer.handleNotificationEvent(message);

        verify(videoActivationService, never()).activateAndRetireSiblings(any());
        assertThat(asset.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        verify(videoAssetRepo).save(asset);
    }

    @Test
    @DisplayName("fans a CONTENT_PUBLISHED broadcast out to every active user")
    void handleContentPublished_fansOutToAllUsers() {
        UUID contentId = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        when(cacheService.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(user1, user2));

        consumer.handleNotificationEvent(Map.of(
            "type", "CONTENT_PUBLISHED",
            "contentTitle", "The Last Horizon",
            "referenceType", "CONTENT",
            "referenceId", contentId.toString()
        ));

        verify(notificationService).sendNotification(user1, NotificationEventType.CONTENT_PUBLISHED,
            "CONTENT", contentId, Map.of("title", "The Last Horizon"));
        verify(notificationService).sendNotification(user2, NotificationEventType.CONTENT_PUBLISHED,
            "CONTENT", contentId, Map.of("title", "The Last Horizon"));
    }

    @Test
    @DisplayName("fans an EPISODE_PUBLISHED broadcast out the same way as CONTENT_PUBLISHED")
    void handleEpisodePublished_fansOutToAllUsers() {
        UUID contentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(cacheService.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(userId));

        consumer.handleNotificationEvent(Map.of(
            "type", "EPISODE_PUBLISHED",
            "contentTitle", "Breaking Bad",
            "referenceType", "CONTENT",
            "referenceId", contentId.toString()
        ));

        verify(notificationService).sendNotification(userId, NotificationEventType.EPISODE_PUBLISHED,
            "CONTENT", contentId, Map.of("title", "Breaking Bad"));
    }

    @Test
    @DisplayName("locks on dedupId, not referenceId -- two episodes on the same show don't collide")
    void handleEpisodePublished_locksOnDedupIdNotReferenceId() {
        // Regression test: EPISODE_PUBLISHED's referenceId is the PARENT SHOW's id (shared by
        // every episode added to it, since the notification deep-links to the show, not the
        // episode). If the idempotency lock were keyed on referenceId, a second episode added to
        // the same already-live show within the lock's TTL would collide with the first
        // episode's still-live lock and be silently dropped as a "duplicate" -- even though it's
        // a genuinely new episode nobody has been told about. dedupId (the episode's own id)
        // must be what the lock actually keys on.
        UUID contentId = UUID.randomUUID();
        UUID episode1Id = UUID.randomUUID();
        UUID episode2Id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(cacheService.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(userId));

        consumer.handleNotificationEvent(Map.of(
            "type", "EPISODE_PUBLISHED",
            "contentTitle", "Breaking Bad",
            "referenceType", "CONTENT",
            "referenceId", contentId.toString(),
            "dedupId", episode1Id.toString()
        ));
        consumer.handleNotificationEvent(Map.of(
            "type", "EPISODE_PUBLISHED",
            "contentTitle", "Breaking Bad",
            "referenceType", "CONTENT",
            "referenceId", contentId.toString(),
            "dedupId", episode2Id.toString()
        ));

        ArgumentCaptor<String> lockKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheService, times(2)).setIfAbsent(lockKeyCaptor.capture(), any(), any());
        List<String> lockKeys = lockKeyCaptor.getAllValues();
        assertThat(lockKeys.get(0)).isNotEqualTo(lockKeys.get(1));
        assertThat(lockKeys.get(0)).contains(episode1Id.toString());
        assertThat(lockKeys.get(1)).contains(episode2Id.toString());
        // Both episodes' lock attempts succeeded (mocked true), so the fan-out ran twice --
        // the second episode was not treated as a duplicate of the first.
        verify(userRepository, times(2)).findAllActiveUserIds();
        verify(notificationService, times(2)).sendNotification(eq(userId), eq(NotificationEventType.EPISODE_PUBLISHED),
            eq("CONTENT"), eq(contentId), any());
    }

    @Test
    @DisplayName("redelivery of the same broadcast is a no-op (idempotency lock)")
    void handleContentPublished_redelivery_isNoOp() {
        UUID contentId = UUID.randomUUID();

        when(cacheService.setIfAbsent(any(), any(), any())).thenReturn(false);

        consumer.handleNotificationEvent(Map.of(
            "type", "CONTENT_PUBLISHED",
            "contentTitle", "The Last Horizon",
            "referenceType", "CONTENT",
            "referenceId", contentId.toString()
        ));

        verifyNoInteractions(userRepository, notificationService);
    }

    @Test
    @DisplayName("one failing user does not stop the rest of the broadcast fan-out")
    void handleContentPublished_oneUserFailureDoesNotAbortBroadcast() {
        UUID contentId = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        when(cacheService.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(user1, user2));
        doThrow(new RuntimeException("boom")).when(notificationService)
            .sendNotification(eq(user1), any(), any(), any(), any());

        consumer.handleNotificationEvent(Map.of(
            "type", "CONTENT_PUBLISHED",
            "contentTitle", "The Last Horizon",
            "referenceType", "CONTENT",
            "referenceId", contentId.toString()
        ));

        verify(notificationService).sendNotification(user2, NotificationEventType.CONTENT_PUBLISHED,
            "CONTENT", contentId, Map.of("title", "The Last Horizon"));
    }

    @Test
    @DisplayName("DEVICE_LOGGED_OUT dispatches straight to the target user, no broadcast fan-out, no dedup lock")
    void deviceLoggedOut_sendsDirectlyToTargetUser() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> message = Map.of(
                "type", "DEVICE_LOGGED_OUT",
                "userId", userId.toString(),
                "deviceName", "Chrome on Windows"
        );

        consumer.handleNotificationEvent(message);

        verify(notificationService).sendNotification(
                eq(userId),
                eq(NotificationEventType.DEVICE_LOGGED_OUT),
                eq("ACCOUNT_SECURITY"),
                eq(userId),
                eq(Map.of("deviceName", "Chrome on Windows"))
        );
        verify(cacheService, never()).setIfAbsent(any(), any(), any());
    }

    @Test
    @DisplayName("DEVICE_LOGGED_OUT falls back to a generic device name when none is provided")
    void deviceLoggedOut_missingDeviceName_fallsBackToGeneric() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> message = Map.of(
                "type", "DEVICE_LOGGED_OUT",
                "userId", userId.toString()
        );

        consumer.handleNotificationEvent(message);

        verify(notificationService).sendNotification(
                eq(userId),
                eq(NotificationEventType.DEVICE_LOGGED_OUT),
                eq("ACCOUNT_SECURITY"),
                eq(userId),
                eq(Map.of("deviceName", "a device"))
        );
    }
}
