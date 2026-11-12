package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.repository.NotificationTemplateRepository;
import com.tinniestudio.api.shared.entity.NotificationTemplate;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
        if (templateRepo.existsByEventTypeAndChannel(req.getEventType(), req.getChannel())) {
            throw new BadRequestException(duplicateMessage(req.getEventType(), req.getChannel()));
        }
        NotificationTemplate t = new NotificationTemplate();
        t.setEventType(req.getEventType());
        t.setTitleTemplate(req.getTitleTemplate());
        t.setBodyTemplate(req.getBodyTemplate());
        t.setChannel(req.getChannel());
        try {
            // saveAndFlush, not save: see ContentService.create for why plain save() doesn't
            // reliably surface the V61 (event_type, channel) constraint violation inside this
            // try/catch — a UUID-generated id defers the INSERT to commit time otherwise. Closes
            // the TOCTOU between the existsByEventTypeAndChannel check above and this write.
            // 400, not 409: deliberately matches the synchronous existsByEventTypeAndChannel
            // check above, not ContentService/SeasonService's 409 convention for the same race —
            // this module's pre-existing duplicate-template check already used 400.
            return NotificationTemplateResponse.from(templateRepo.saveAndFlush(t));
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException(duplicateMessage(req.getEventType(), req.getChannel()));
        }
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
        if (req.getChannel() != null) {
            // Since V61, (event_type, channel) is the unique pair — changing channel on an
            // existing template can now collide with another active row the same way create()
            // can, which couldn't happen before V61 (uniqueness was on event_type alone, and
            // update() never changes eventType).
            if (!req.getChannel().equals(t.getChannel())
                    && templateRepo.existsByEventTypeAndChannel(t.getEventType(), req.getChannel())) {
                throw new BadRequestException(duplicateMessage(t.getEventType(), req.getChannel()));
            }
            t.setChannel(req.getChannel());
        }
        if (req.getIsActive() != null) t.setIsActive(req.getIsActive());
        try {
            // saveAndFlush, not save: same TOCTOU reasoning as create() above.
            return NotificationTemplateResponse.from(templateRepo.saveAndFlush(t));
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException(duplicateMessage(t.getEventType(), t.getChannel()));
        }
    }

    private String duplicateMessage(com.tinniestudio.api.shared.entity.DomainEnums.NotificationEventType eventType,
                                     com.tinniestudio.api.shared.entity.DomainEnums.NotificationChannel channel) {
        return "Template for event type " + eventType + " on channel " + channel + " already exists";
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
