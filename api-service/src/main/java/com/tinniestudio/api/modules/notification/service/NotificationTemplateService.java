package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import java.util.List;
import java.util.UUID;

public interface NotificationTemplateService {
    NotificationTemplateResponse create(CreateNotificationTemplateRequest req);
    List<NotificationTemplateResponse> list();
    NotificationTemplateResponse update(UUID id, UpdateNotificationTemplateRequest req);
    void delete(UUID id);
}
