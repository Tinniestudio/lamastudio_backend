package com.tinniestudio.api.modules.notification.repository;

import com.tinniestudio.api.shared.entity.NotificationTemplate;
import com.tinniestudio.api.shared.entity.DomainEnums.NotificationEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {
    Optional<NotificationTemplate> findByEventTypeAndIsActiveTrue(NotificationEventType eventType);
    boolean existsByEventType(NotificationEventType eventType);
}
