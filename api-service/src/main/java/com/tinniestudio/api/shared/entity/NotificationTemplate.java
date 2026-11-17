package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "notification_templates",
    uniqueConstraints = @UniqueConstraint(columnNames = {"event_type", "channel"})
)
@Getter @Setter @NoArgsConstructor
public class NotificationTemplate extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationEventType eventType;

    @Column(nullable = false)
    private String titleTemplate;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String bodyTemplate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel = NotificationChannel.IN_APP;

    @Column(nullable = false)
    private Boolean isActive = true;
}
