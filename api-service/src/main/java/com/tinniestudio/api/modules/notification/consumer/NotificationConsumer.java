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
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    // Not released after processing (unlike a mutual-exclusion job lock) — this is a dedup/seen
    // marker, not a concurrency lock. A given (eventType, dedupId) is only ever meant to be
    // broadcast once, ever, so the lock should simply outlive any realistic redelivery window
    // rather than being deleted in a finally block. Known tradeoff: if a failure (e.g. a transient
    // DB error from findAllActiveUserIds, or a crash mid-fan-out) happens after the lock is
    // acquired, that exact broadcast cannot be retried for the full TTL — not even by a human
    // manually republishing the same message. Recovering from that requires deleting the
    // "tinnie:lock:notif-broadcast:*" key directly in Redis.
    private static final Duration BROADCAST_DEDUP_TTL = Duration.ofHours(24);

    private final NotificationService notificationService;
    private final VideoAssetRepository videoAssetRepo;
    private final ContentRepository contentRepo;
    private final VideoActivationService videoActivationService;
    private final UserRepository userRepository;
    private final CacheService cacheService;

    @RabbitListener(queues = RabbitConfig.QUEUE_NOTIFICATIONS)
    public void handleNotificationEvent(Map<String, Object> message) {
        String type = (String) message.get("type");
        switch (type) {
            case "CONTENT_PROCESSED" -> handleContentProcessed(message);
            case "CONTENT_PUBLISHED" -> handleBroadcast(message, NotificationEventType.CONTENT_PUBLISHED);
            case "EPISODE_PUBLISHED" -> handleBroadcast(message, NotificationEventType.EPISODE_PUBLISHED);
            case "DEVICE_LOGGED_OUT" -> handleDeviceLoggedOut(message);
            case "TICKET_REPLIED" -> handleTicketReplied(message);
            // A message with no "type" key (type == null here) must not reach a plain switch on a
            // String — that throws NullPointerException, and with no DLX configured on this queue
            // and AcknowledgeMode.AUTO, an uncaught exception here means an immediate tight-loop
            // requeue instead of a graceful ignore.
            case null, default -> log.debug("Ignoring notification event type: {}", type);
        }
    }

    private void handleContentProcessed(Map<String, Object> message) {
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

    // Unlike handleBroadcast, this targets exactly one user (the one who just got signed out of
    // a device) — no fan-out over findAllActiveUserIds(), and no dedup lock, since a given login
    // resolution can only ever fire this once (the resolution token is single-use, enforced in
    // AuthService before this message is even published).
    private void handleDeviceLoggedOut(Map<String, Object> message) {
        try {
            UUID userId = UUID.fromString((String) message.get("userId"));
            String deviceName = (String) message.getOrDefault("deviceName", "a device");
            notificationService.sendNotification(
                    userId,
                    NotificationEventType.DEVICE_LOGGED_OUT,
                    "ACCOUNT_SECURITY",
                    userId,
                    Map.of("deviceName", deviceName)
            );
        } catch (Exception e) {
            log.error("Error processing DEVICE_LOGGED_OUT notification: {}", e.getMessage(), e);
        }
    }

    // Single-target, same style as handleDeviceLoggedOut — the ticket reply itself is the only
    // event that can ever fire this for a given (ticketId, replyAt) occurrence, so no dedup lock
    // is needed.
    private void handleTicketReplied(Map<String, Object> message) {
        try {
            UUID userId = UUID.fromString((String) message.get("userId"));
            UUID ticketId = UUID.fromString((String) message.get("ticketId"));
            // getOrDefault, not a bare get: Map.of() below throws NullPointerException on a null
            // value, which this method's catch would swallow as a generic error — silently
            // dropping the user's reply notification with no retry, same failure mode
            // handleDeviceLoggedOut's deviceName fallback exists to avoid.
            String subject = (String) message.getOrDefault("subject", "your ticket");
            notificationService.sendNotification(
                    userId,
                    NotificationEventType.TICKET_REPLIED,
                    "TICKET",
                    ticketId,
                    Map.of("subject", subject)
            );
        } catch (Exception e) {
            log.error("Error processing TICKET_REPLIED notification: {}", e.getMessage(), e);
        }
    }

    private void handleBroadcast(Map<String, Object> message, NotificationEventType eventType) {
        try {
            String contentTitle = (String) message.get("contentTitle");
            String referenceType = (String) message.get("referenceType");
            UUID referenceId = UUID.fromString((String) message.get("referenceId"));
            // dedupId identifies THIS broadcast occurrence, distinct from referenceId (the
            // notification's deep-link target). For CONTENT_PUBLISHED they're equal (a content
            // id really is one-time), but for EPISODE_PUBLISHED referenceId is the parent show's
            // id — reused across every episode added to that show — while dedupId is the
            // specific season/episode's own id, so adding episode 2 doesn't collide with and get
            // silently dropped by episode 1's still-live lock.
            String dedupId = (String) message.getOrDefault("dedupId", message.get("referenceId"));

            String lockKey = "tinnie:lock:notif-broadcast:" + eventType + ":" + dedupId;
            if (!cacheService.setIfAbsent(lockKey, "sent", BROADCAST_DEDUP_TTL)) {
                log.debug("Broadcast for {} {} already processed, skipping redelivery", eventType, referenceId);
                return;
            }

            List<UUID> userIds = userRepository.findAllActiveUserIds();
            Map<String, String> placeholders = Map.of("title", contentTitle);
            for (UUID userId : userIds) {
                try {
                    notificationService.sendNotification(userId, eventType, referenceType, referenceId, placeholders);
                } catch (Exception e) {
                    log.warn("Broadcast notification failed for user {} event {}: {}", userId, eventType, e.getMessage());
                }
            }
            log.info("Broadcast {} for {} {} sent to {} users", eventType, referenceType, referenceId, userIds.size());
        } catch (Exception e) {
            log.error("Error processing {} broadcast: {}", eventType, e.getMessage(), e);
        }
    }
}
