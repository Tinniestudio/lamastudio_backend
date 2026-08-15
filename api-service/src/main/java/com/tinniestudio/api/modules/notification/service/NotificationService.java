package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface NotificationService {
    void sendNotification(UUID userId, NotificationEventType eventType,
                          String referenceType, UUID referenceId);
    Page<NotificationResponse> listForUser(UUID userId, Pageable pageable);
    void markRead(UUID userId, UUID notificationId);
    void markAllRead(UUID userId);
    long getUnreadCount(UUID userId);
    List<NotificationPreferenceResponse> getPreferences(UUID userId);
    NotificationPreferenceResponse updatePreference(UUID userId, UpdatePreferenceRequest req);
}
