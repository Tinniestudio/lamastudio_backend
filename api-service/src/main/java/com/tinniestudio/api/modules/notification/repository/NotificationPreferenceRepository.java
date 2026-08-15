package com.tinniestudio.api.modules.notification.repository;

import com.tinniestudio.api.shared.entity.NotificationPreference;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
    List<NotificationPreference> findByUserId(UUID userId);
    Optional<NotificationPreference> findByUserIdAndChannelAndEventType(
        UUID userId, NotificationChannel channel, NotificationEventType eventType);
}
