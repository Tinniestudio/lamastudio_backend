package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdatePreferenceRequest {
    @NotNull NotificationChannel channel;
    @NotNull NotificationEventType eventType;
    @NotNull Boolean isEnabled;
}
