package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.NotificationTemplate;
import java.time.Instant;
import java.util.UUID;

public record NotificationTemplateResponse(
    UUID id,
    String eventType,
    String titleTemplate,
    String bodyTemplate,
    String channel,
    Boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {
    public static NotificationTemplateResponse from(NotificationTemplate t) {
        return new NotificationTemplateResponse(
            t.getId(), t.getEventType().name(), t.getTitleTemplate(),
            t.getBodyTemplate(), t.getChannel().name(), t.getIsActive(),
            t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
