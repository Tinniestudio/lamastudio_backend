package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.UpdatePreferenceRequest;
import com.tinniestudio.api.modules.notification.repository.*;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepo;
    @Mock NotificationTemplateRepository templateRepo;
    @Mock NotificationPreferenceRepository preferenceRepo;
    @InjectMocks NotificationServiceImpl service;

    @Test
    void sendNotification_createsNotification_whenTemplateExists() {
        UUID userId = UUID.randomUUID();
        NotificationTemplate template = new NotificationTemplate();
        template.setEventType(NotificationEventType.CONTENT_PROCESSED);
        template.setTitleTemplate("Ready");
        template.setBodyTemplate("Your content is ready");
        template.setChannel(NotificationChannel.IN_APP);
        template.setIsActive(true);

        when(templateRepo.findByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(template));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getIsRead()).isFalse();
    }

    @Test
    void sendNotification_skips_whenNoTemplate() {
        when(templateRepo.findByEventTypeAndIsActiveTrue(any())).thenReturn(Optional.empty());
        service.sendNotification(UUID.randomUUID(), NotificationEventType.CONTENT_PROCESSED, null, null);
        verify(notificationRepo, never()).save(any());
    }

    @Test
    void sendNotification_suppressed_whenPreferenceDisabled() {
        UUID userId = UUID.randomUUID();
        NotificationTemplate template = new NotificationTemplate();
        template.setEventType(NotificationEventType.CONTENT_PROCESSED);
        template.setTitleTemplate("Ready");
        template.setBodyTemplate("Your content is ready");
        template.setChannel(NotificationChannel.IN_APP);
        template.setIsActive(true);

        NotificationPreference pref = new NotificationPreference();
        pref.setUserId(userId);
        pref.setChannel(NotificationChannel.IN_APP);
        pref.setEventType(NotificationEventType.CONTENT_PROCESSED);
        pref.setIsEnabled(false);

        when(templateRepo.findByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(template));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(pref));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo, never()).save(any());
    }

    @Test
    void sendNotification_creates_whenNoPreferenceRow() {
        UUID userId = UUID.randomUUID();
        NotificationTemplate template = new NotificationTemplate();
        template.setEventType(NotificationEventType.CONTENT_PROCESSED);
        template.setTitleTemplate("Ready");
        template.setBodyTemplate("Your content is ready");
        template.setChannel(NotificationChannel.IN_APP);
        template.setIsActive(true);

        when(templateRepo.findByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(template));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.empty());
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo).save(any());
    }

    @Test
    void sendNotification_creates_whenPreferenceExplicitlyEnabled() {
        UUID userId = UUID.randomUUID();
        NotificationTemplate template = new NotificationTemplate();
        template.setEventType(NotificationEventType.CONTENT_PROCESSED);
        template.setTitleTemplate("Ready");
        template.setBodyTemplate("Your content is ready");
        template.setChannel(NotificationChannel.IN_APP);
        template.setIsActive(true);

        NotificationPreference pref = new NotificationPreference();
        pref.setUserId(userId);
        pref.setChannel(NotificationChannel.IN_APP);
        pref.setEventType(NotificationEventType.CONTENT_PROCESSED);
        pref.setIsEnabled(true);

        when(templateRepo.findByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(template));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(pref));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo).save(any());
    }

    @Test
    void getUnreadCount_returnsCount() {
        UUID userId = UUID.randomUUID();
        when(notificationRepo.countByUserIdAndIsReadFalse(userId)).thenReturn(5L);
        assertThat(service.getUnreadCount(userId)).isEqualTo(5L);
    }

    @Test
    void markRead_throwsNotFound_whenNotificationMissing() {
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        when(notificationRepo.findByIdAndUserId(notifId, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markRead(userId, notifId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatePreference_createsIfAbsent() {
        UUID userId = UUID.randomUUID();
        UpdatePreferenceRequest req = new UpdatePreferenceRequest();
        req.setChannel(NotificationChannel.IN_APP);
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setIsEnabled(false);

        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.empty());
        when(preferenceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.updatePreference(userId, req);
        assertThat(result.isEnabled()).isFalse();
    }
}
