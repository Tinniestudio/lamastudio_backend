package com.tinniestudio.api.modules.notification.repository;

import com.tinniestudio.api.shared.entity.NotificationTemplate;
import com.tinniestudio.api.shared.entity.DomainEnums.NotificationEventType;
import com.tinniestudio.api.shared.entity.DomainEnums.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {
    Optional<NotificationTemplate> findByEventTypeAndIsActiveTrue(NotificationEventType eventType);
    List<NotificationTemplate> findAllByEventTypeAndIsActiveTrue(NotificationEventType eventType);
    boolean existsByEventTypeAndChannel(NotificationEventType eventType, NotificationChannel channel);
}
