package com.tinniestudio.api.modules.notification.consumer;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.notification.service.NotificationService;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
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

        verify(videoAssetRepo).deactivateOtherAssets(contentId, VideoAssetType.MAIN_VIDEO, assetId);
        ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
        verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
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

        consumer.handleNotificationEvent(message);

        verify(videoAssetRepo, never()).deactivateOtherAssets(any(), any(), any());
    }
}
