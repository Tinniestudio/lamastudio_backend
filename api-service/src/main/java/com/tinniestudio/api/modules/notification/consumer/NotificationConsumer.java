package com.tinniestudio.api.modules.notification.consumer;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.notification.service.NotificationService;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;
    private final VideoAssetRepository videoAssetRepo;
    private final ContentRepository contentRepo;

    @RabbitListener(queues = RabbitConfig.QUEUE_NOTIFICATIONS)
    public void handleNotificationEvent(Map<String, Object> message) {
        String type = (String) message.get("type");
        if (!"CONTENT_PROCESSED".equals(type)) {
            log.debug("Ignoring notification event type: {}", type);
            return;
        }
        try {
            UUID assetId = UUID.fromString((String) message.get("videoAssetId"));
            UUID contentId = UUID.fromString((String) message.get("contentId"));
            String status = (String) message.get("status");

            VideoAsset asset = videoAssetRepo.findById(assetId).orElse(null);
            if (asset == null) {
                log.warn("VideoAsset not found: {}", assetId);
                return;
            }
            asset.setProcessingStatus("READY".equals(status) ? ProcessingStatus.READY : ProcessingStatus.FAILED);
            videoAssetRepo.save(asset);

            Content content = contentRepo.findById(contentId).orElse(null);
            if (content == null) {
                log.warn("Content not found for notification: {}", contentId);
                return;
            }
            notificationService.sendNotification(
                content.getCreatedBy(),
                NotificationEventType.CONTENT_PROCESSED,
                "VIDEO_ASSET",
                assetId
            );
        } catch (Exception e) {
            log.error("Error processing CONTENT_PROCESSED notification: {}", e.getMessage(), e);
        }
    }
}
