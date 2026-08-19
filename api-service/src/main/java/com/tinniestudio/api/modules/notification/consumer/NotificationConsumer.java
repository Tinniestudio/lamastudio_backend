package com.tinniestudio.api.modules.notification.consumer;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.notification.service.NotificationService;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.modules.upload.service.VideoActivationService;
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
    private final VideoActivationService videoActivationService;

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
            boolean succeeded = "READY".equals(status);
            asset.setProcessingStatus(succeeded ? ProcessingStatus.READY : ProcessingStatus.FAILED);
            if (succeeded && asset.getContent() != null) {
                // Auto-retire: this asset becomes "the" active one for its (content, assetType)
                // pair, every sibling that was previously active gets flipped off. Guarded on
                // asset.getContent() != null because a video processed before B6 (video-linking
                // fix) landed may have no content link at all — nothing to retire against, and
                // no isActive semantics apply to an unlinked asset.
                //
                // Delegated to VideoActivationService so the sibling-retire UPDATE and this
                // asset's own save() commit in one transaction — done inline in the same method
                // (no enclosing @Transactional here), a crash between the two writes could
                // otherwise leave zero active assets for this (content, assetType) pair.
                // processingStatus was already set above on this same in-memory entity, so the
                // service's own save() persists both fields together — no separate save needed.
                videoActivationService.activateAndRetireSiblings(asset);
            } else {
                if (succeeded) {
                    log.warn("VideoAsset {} processed successfully but has no content link " +
                            "(pre-B6 legacy data?) — cannot activate", assetId);
                }
                videoAssetRepo.save(asset);
            }

            Content content = contentRepo.findById(contentId).orElse(null);
            if (content == null) {
                log.warn("Content not found for notification: {}", contentId);
                return;
            }
            // CONTENT, not VIDEO_ASSET: the notification is about a piece of content finishing
            // processing, and the frontend has a content page to deep-link to — it has no
            // video-asset detail view. contentId was already resolved above (it's how we found
            // the recipient), it just wasn't what got sent as the reference before this fix.
            //
            // Known limitation: media-worker's publisher (VideoProcessingService) only includes a
            // real contentId in this message when VideoAsset.contentId is set, which nothing in
            // the upload-completion path populates yet (see UploadService.completeSession) — so
            // in practice this branch is rarely reached today; contentRepo.findById(contentId)
            // above returns empty and we return early instead. That's the video-upload-to-content
            // linking gap (tracked separately, not fixed here) — this fix is still correct for the
            // day that gap closes, and is a strict improvement over the old VIDEO_ASSET reference,
            // which could never deep-link correctly even with a valid id.
            notificationService.sendNotification(
                content.getCreatedBy(),
                NotificationEventType.CONTENT_PROCESSED,
                "CONTENT",
                contentId
            );
        } catch (Exception e) {
            log.error("Error processing CONTENT_PROCESSED notification: {}", e.getMessage(), e);
        }
    }
}
