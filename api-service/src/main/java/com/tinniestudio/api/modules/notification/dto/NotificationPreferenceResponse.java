package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.NotificationPreference;
import java.util.UUID;

public record NotificationPreferenceResponse(
    UUID id,
    String channel,
    String eventType,
    Boolean isEnabled
) {
    public static NotificationPreferenceResponse from(NotificationPreference p) {
        return new NotificationPreferenceResponse(
            p.getId(), p.getChannel().name(), p.getEventType().name(), p.getIsEnabled()
        );
    }
}
