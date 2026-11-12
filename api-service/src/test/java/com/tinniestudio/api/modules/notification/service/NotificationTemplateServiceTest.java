package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.repository.NotificationTemplateRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.entity.NotificationTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {

    @Mock NotificationTemplateRepository templateRepo;
    @InjectMocks NotificationTemplateServiceImpl service;

    @Test
    void create_savesTemplate() {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("Your video is ready");
        req.setBodyTemplate("Your content has been processed.");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateRepo.existsByEventTypeAndChannel(NotificationEventType.CONTENT_PROCESSED, NotificationChannel.IN_APP))
            .thenReturn(false);
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(req);
        assertThat(result.eventType()).isEqualTo("CONTENT_PROCESSED");
        verify(templateRepo).saveAndFlush(any(NotificationTemplate.class));
    }

    @Test
    void create_throwsBadRequest_whenSameEventTypeAndChannelAlreadyExists() {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("T"); req.setBodyTemplate("B");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateRepo.existsByEventTypeAndChannel(NotificationEventType.CONTENT_PROCESSED, NotificationChannel.IN_APP))
            .thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(com.tinniestudio.api.shared.exception.BadRequestException.class);
    }

    @Test
    void create_allows_whenSameEventTypeButDifferentChannel() {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("T"); req.setBodyTemplate("B");
        req.setChannel(NotificationChannel.EMAIL);

        when(templateRepo.existsByEventTypeAndChannel(NotificationEventType.CONTENT_PROCESSED, NotificationChannel.EMAIL))
            .thenReturn(false);
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(req);
        assertThat(result.eventType()).isEqualTo("CONTENT_PROCESSED");
        verify(templateRepo).saveAndFlush(any(NotificationTemplate.class));
    }

    @Test
    void create_throwsBadRequest_whenConcurrentInsertRacesPastTheExistsCheck() {
        // TOCTOU: another request could insert the same (event_type, channel) pair between
        // existsByEventTypeAndChannel returning false and this save committing. saveAndFlush
        // surfaces that as the real DB constraint violation instead of a stale silent success.
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("T"); req.setBodyTemplate("B");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateRepo.existsByEventTypeAndChannel(NotificationEventType.CONTENT_PROCESSED, NotificationChannel.IN_APP))
            .thenReturn(false);
        when(templateRepo.saveAndFlush(any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(com.tinniestudio.api.shared.exception.BadRequestException.class);
    }

    @Test
    void update_throwsNotFound_whenMissing() {
        when(templateRepo.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), new UpdateNotificationTemplateRequest()))
            .isInstanceOf(com.tinniestudio.api.shared.exception.ResourceNotFoundException.class);
    }

    @Test
    void update_allows_whenChannelUnchanged() {
        UUID id = UUID.randomUUID();
        NotificationTemplate existing = new NotificationTemplate();
        existing.setEventType(NotificationEventType.CONTENT_PROCESSED);
        existing.setChannel(NotificationChannel.IN_APP);
        existing.setTitleTemplate("Old title");
        existing.setBodyTemplate("Old body");

        var req = new UpdateNotificationTemplateRequest();
        req.setTitleTemplate("New title");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(id, req);

        assertThat(result.titleTemplate()).isEqualTo("New title");
        verify(templateRepo, never()).existsByEventTypeAndChannel(any(), any());
    }

    @Test
    void update_throwsBadRequest_whenChangingChannelToOneThatAlreadyExists() {
        UUID id = UUID.randomUUID();
        NotificationTemplate existing = new NotificationTemplate();
        existing.setEventType(NotificationEventType.CONTENT_PROCESSED);
        existing.setChannel(NotificationChannel.IN_APP);
        existing.setTitleTemplate("T");
        existing.setBodyTemplate("B");

        var req = new UpdateNotificationTemplateRequest();
        req.setChannel(NotificationChannel.EMAIL);

        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));
        when(templateRepo.existsByEventTypeAndChannel(NotificationEventType.CONTENT_PROCESSED, NotificationChannel.EMAIL))
            .thenReturn(true);

        assertThatThrownBy(() -> service.update(id, req))
            .isInstanceOf(com.tinniestudio.api.shared.exception.BadRequestException.class);
        verify(templateRepo, never()).saveAndFlush(any());
    }

    @Test
    void update_allows_whenChangingChannelToFreeOne() {
        UUID id = UUID.randomUUID();
        NotificationTemplate existing = new NotificationTemplate();
        existing.setEventType(NotificationEventType.CONTENT_PROCESSED);
        existing.setChannel(NotificationChannel.IN_APP);
        existing.setTitleTemplate("T");
        existing.setBodyTemplate("B");

        var req = new UpdateNotificationTemplateRequest();
        req.setChannel(NotificationChannel.EMAIL);

        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));
        when(templateRepo.existsByEventTypeAndChannel(NotificationEventType.CONTENT_PROCESSED, NotificationChannel.EMAIL))
            .thenReturn(false);
        when(templateRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(id, req);

        assertThat(result.channel()).isEqualTo("EMAIL");
    }

    @Test
    void update_throwsBadRequest_whenConcurrentChannelChangeRacesPastExistsCheck() {
        UUID id = UUID.randomUUID();
        NotificationTemplate existing = new NotificationTemplate();
        existing.setEventType(NotificationEventType.CONTENT_PROCESSED);
        existing.setChannel(NotificationChannel.IN_APP);
        existing.setTitleTemplate("T");
        existing.setBodyTemplate("B");

        var req = new UpdateNotificationTemplateRequest();
        req.setChannel(NotificationChannel.EMAIL);

        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));
        when(templateRepo.existsByEventTypeAndChannel(NotificationEventType.CONTENT_PROCESSED, NotificationChannel.EMAIL))
            .thenReturn(false);
        when(templateRepo.saveAndFlush(any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.update(id, req))
            .isInstanceOf(com.tinniestudio.api.shared.exception.BadRequestException.class);
    }

    @Test
    void list_returnsAll() {
        when(templateRepo.findAll()).thenReturn(List.of());
        assertThat(service.list()).isEmpty();
    }

    @Test
    void delete_throwsNotFound_whenMissing() {
        when(templateRepo.existsById(any())).thenReturn(false);
        assertThatThrownBy(() -> service.delete(UUID.randomUUID()))
            .isInstanceOf(com.tinniestudio.api.shared.exception.ResourceNotFoundException.class);
    }
}
