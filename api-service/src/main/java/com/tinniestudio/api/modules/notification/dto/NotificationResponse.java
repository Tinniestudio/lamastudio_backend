package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.Notification;
import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
    UUID id,
    String eventType,
    String title,
    String body,
    String channel,
    Boolean isRead,
    Instant readAt,
    String referenceType,
    UUID referenceId,
    Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
            n.getId(), n.getEventType().name(), n.getTitle(), n.getBody(),
            n.getChannel().name(), n.getIsRead(), n.getReadAt(),
            n.getReferenceType(), n.getReferenceId(), n.getCreatedAt()
        );
    }
}
