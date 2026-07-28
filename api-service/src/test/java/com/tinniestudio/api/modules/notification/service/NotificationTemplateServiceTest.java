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

        when(templateRepo.existsByEventType(NotificationEventType.CONTENT_PROCESSED)).thenReturn(false);
        when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(req);
        assertThat(result.eventType()).isEqualTo("CONTENT_PROCESSED");
        verify(templateRepo).save(any(NotificationTemplate.class));
    }

    @Test
    void create_throwsBadRequest_whenEventTypeAlreadyExists() {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("T"); req.setBodyTemplate("B");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateRepo.existsByEventType(NotificationEventType.CONTENT_PROCESSED)).thenReturn(true);

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
