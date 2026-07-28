package com.tinniestudio.api.modules.notification.consumer;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.notification.service.NotificationService;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        verify(notificationService).sendNotification(
            eq(creatorId), eq(NotificationEventType.CONTENT_PROCESSED),
            eq("VIDEO_ASSET"), eq(assetId));
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
}
