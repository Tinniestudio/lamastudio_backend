package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.repository.*;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepo;
    private final NotificationTemplateRepository templateRepo;
    private final NotificationPreferenceRepository preferenceRepo;

    @Override
    @Transactional
    public void sendNotification(UUID userId, NotificationEventType eventType,
                                  String referenceType, UUID referenceId) {
        Optional<NotificationTemplate> templateOpt =
            templateRepo.findByEventTypeAndIsActiveTrue(eventType);
        if (templateOpt.isEmpty()) {
            log.debug("No active template for event type: {}. Skipping.", eventType);
            return;
        }
        NotificationTemplate template = templateOpt.get();

        boolean suppressed = preferenceRepo
            .findByUserIdAndChannelAndEventType(userId, template.getChannel(), eventType)
            .map(pref -> !pref.getIsEnabled())
            .orElse(false);
        if (suppressed) {
            log.debug("User {} has disabled event type {} on channel {}. Skipping.",
                userId, eventType, template.getChannel());
            return;
        }

        Notification n = new Notification();
        n.setUserId(userId);
        n.setEventType(eventType);
        n.setTitle(template.getTitleTemplate());
        n.setBody(template.getBodyTemplate());
        n.setChannel(template.getChannel());
        n.setReferenceType(referenceType);
        n.setReferenceId(referenceId);
        n.setSentAt(Instant.now());
        notificationRepo.save(n);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> listForUser(UUID userId, Pageable pageable) {
        return notificationRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(NotificationResponse::from);
    }

    @Override
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification n = notificationRepo.findByIdAndUserId(notificationId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));
        if (!n.getIsRead()) {
            n.setIsRead(true);
            n.setReadAt(Instant.now());
            notificationRepo.save(n);
        }
    }

    @Override
    @Transactional
    public void markAllRead(UUID userId) {
        notificationRepo.markAllReadForUser(userId, Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepo.countByUserIdAndIsReadFalse(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> getPreferences(UUID userId) {
        return preferenceRepo.findByUserId(userId).stream()
            .map(NotificationPreferenceResponse::from).toList();
    }

    @Override
    @Transactional
    public NotificationPreferenceResponse updatePreference(UUID userId, UpdatePreferenceRequest req) {
        NotificationPreference pref = preferenceRepo
            .findByUserIdAndChannelAndEventType(userId, req.getChannel(), req.getEventType())
            .orElseGet(() -> {
                NotificationPreference p = new NotificationPreference();
                p.setUserId(userId);
                p.setChannel(req.getChannel());
                p.setEventType(req.getEventType());
                return p;
            });
        pref.setIsEnabled(req.getIsEnabled());
        return NotificationPreferenceResponse.from(preferenceRepo.save(pref));
    }
}
