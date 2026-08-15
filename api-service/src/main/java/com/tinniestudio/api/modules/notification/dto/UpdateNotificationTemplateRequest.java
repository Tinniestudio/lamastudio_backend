package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import lombok.Data;

@Data
public class UpdateNotificationTemplateRequest {
    String titleTemplate;
    String bodyTemplate;
    NotificationChannel channel;
    Boolean isActive;
}
