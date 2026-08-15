package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter @Setter @NoArgsConstructor
public class Notification extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationEventType eventType;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel = NotificationChannel.IN_APP;

    @Column(nullable = false)
    private Boolean isRead = false;

    private Instant readAt;

    private String referenceType;

    private UUID referenceId;

    private Instant sentAt;

    @Column(nullable = false)
    private Integer retryCount = 0;
}
