package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.repository.NotificationTemplateRepository;
import com.tinniestudio.api.shared.entity.NotificationTemplate;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationTemplateServiceImpl implements NotificationTemplateService {

    private final NotificationTemplateRepository templateRepo;

    @Override
    @Transactional
    public NotificationTemplateResponse create(CreateNotificationTemplateRequest req) {
        if (templateRepo.existsByEventType(req.getEventType())) {
            throw new BadRequestException("Template for event type already exists: " + req.getEventType());
        }
        NotificationTemplate t = new NotificationTemplate();
        t.setEventType(req.getEventType());
        t.setTitleTemplate(req.getTitleTemplate());
        t.setBodyTemplate(req.getBodyTemplate());
        t.setChannel(req.getChannel());
        return NotificationTemplateResponse.from(templateRepo.save(t));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> list() {
        return templateRepo.findAll().stream().map(NotificationTemplateResponse::from).toList();
    }

    @Override
    @Transactional
    public NotificationTemplateResponse update(UUID id, UpdateNotificationTemplateRequest req) {
        NotificationTemplate t = templateRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Notification template not found: " + id));
        if (req.getTitleTemplate() != null) t.setTitleTemplate(req.getTitleTemplate());
        if (req.getBodyTemplate() != null) t.setBodyTemplate(req.getBodyTemplate());
        if (req.getChannel() != null) t.setChannel(req.getChannel());
        if (req.getIsActive() != null) t.setIsActive(req.getIsActive());
        return NotificationTemplateResponse.from(templateRepo.save(t));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        if (!templateRepo.existsById(id)) {
            throw new ResourceNotFoundException("Notification template not found: " + id);
        }
        templateRepo.deleteById(id);
    }
}
