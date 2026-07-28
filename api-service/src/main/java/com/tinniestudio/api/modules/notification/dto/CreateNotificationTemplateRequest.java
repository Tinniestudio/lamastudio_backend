package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateNotificationTemplateRequest {
    @NotNull NotificationEventType eventType;
    @NotBlank String titleTemplate;
    @NotBlank String bodyTemplate;
    @NotNull NotificationChannel channel;
}
