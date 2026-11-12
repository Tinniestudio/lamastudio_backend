package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.UpdatePreferenceRequest;
import com.tinniestudio.api.modules.notification.repository.*;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.email.EmailRequest;
import com.tinniestudio.api.shared.email.ResendEmailService;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
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
    @Mock UserRepository userRepository;
    @Mock ResendEmailService resendEmailService;
    @InjectMocks NotificationServiceImpl service;

    private NotificationTemplate inAppTemplate() {
        NotificationTemplate t = new NotificationTemplate();
        t.setEventType(NotificationEventType.CONTENT_PROCESSED);
        t.setTitleTemplate("Ready");
        t.setBodyTemplate("Your content is ready");
        t.setChannel(NotificationChannel.IN_APP);
        t.setIsActive(true);
        return t;
    }

    private NotificationTemplate emailTemplate() {
        NotificationTemplate t = new NotificationTemplate();
        t.setEventType(NotificationEventType.CONTENT_PROCESSED);
        t.setTitleTemplate("Your content is ready");
        t.setBodyTemplate("Hi — your content has finished processing.");
        t.setChannel(NotificationChannel.EMAIL);
        t.setIsActive(true);
        return t;
    }

    @Test
    void sendNotification_createsNotification_whenInAppTemplateExists() {
        UUID userId = UUID.randomUUID();
        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate()));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getIsRead()).isFalse();
        verifyNoInteractions(resendEmailService);
    }

    @Test
    void sendNotification_skips_whenNoTemplate() {
        when(templateRepo.findAllByEventTypeAndIsActiveTrue(any())).thenReturn(List.of());
        service.sendNotification(UUID.randomUUID(), NotificationEventType.CONTENT_PROCESSED, null, null);
        verify(notificationRepo, never()).save(any());
        verifyNoInteractions(resendEmailService);
    }

    @Test
    void sendNotification_suppressed_whenPreferenceDisabled() {
        UUID userId = UUID.randomUUID();
        NotificationPreference pref = new NotificationPreference();
        pref.setUserId(userId);
        pref.setChannel(NotificationChannel.IN_APP);
        pref.setEventType(NotificationEventType.CONTENT_PROCESSED);
        pref.setIsEnabled(false);

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate()));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(pref));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo, never()).save(any());
    }

    @Test
    void sendNotification_creates_whenNoPreferenceRow() {
        UUID userId = UUID.randomUUID();
        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate()));
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
        NotificationPreference pref = new NotificationPreference();
        pref.setUserId(userId);
        pref.setChannel(NotificationChannel.IN_APP);
        pref.setEventType(NotificationEventType.CONTENT_PROCESSED);
        pref.setIsEnabled(true);

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate()));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(pref));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo).save(any());
    }

    @Test
    void sendNotification_sendsRealEmail_forEmailChannelTemplate_andDoesNotCreateInAppRow() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("viewer@example.com");

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(emailTemplate()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        ArgumentCaptor<EmailRequest> captor = ArgumentCaptor.forClass(EmailRequest.class);
        verify(resendEmailService).sendEmail(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("viewer@example.com");
        assertThat(captor.getValue().subject()).isEqualTo("Your content is ready");
        verify(notificationRepo, never()).save(any());
    }

    @Test
    void sendNotification_deliversBothChannels_whenBothTemplatesActiveAndBothEnabled() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("viewer@example.com");

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate(), emailTemplate()));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo).save(any());
        verify(resendEmailService).sendEmail(any());
    }

    @Test
    void sendNotification_onlyEmail_whenInAppDisabledButEmailEnabled() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("viewer@example.com");

        NotificationPreference inAppDisabled = new NotificationPreference();
        inAppDisabled.setUserId(userId);
        inAppDisabled.setChannel(NotificationChannel.IN_APP);
        inAppDisabled.setEventType(NotificationEventType.CONTENT_PROCESSED);
        inAppDisabled.setIsEnabled(false);

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate(), emailTemplate()));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(inAppDisabled));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.EMAIL, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo, never()).save(any());
        verify(resendEmailService).sendEmail(any());
    }

    @Test
    void sendNotification_substitutesTitlePlaceholder_inBothTitleAndBody() {
        UUID userId = UUID.randomUUID();
        NotificationTemplate template = new NotificationTemplate();
        template.setEventType(NotificationEventType.CONTENT_PROCESSED);
        template.setTitleTemplate("{title} is now available");
        template.setBodyTemplate("Check out {title} today!");
        template.setChannel(NotificationChannel.IN_APP);
        template.setIsActive(true);

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(template));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID(),
            Map.of("title", "The Last Horizon"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("The Last Horizon is now available");
        assertThat(captor.getValue().getBody()).isEqualTo("Check out The Last Horizon today!");
    }

    @Test
    void sendNotification_fourArgOverload_behavesAsNoPlaceholders() {
        UUID userId = UUID.randomUUID();
        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate()));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Ready");
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
