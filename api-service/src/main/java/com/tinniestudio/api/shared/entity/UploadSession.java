package com.tinniestudio.api.shared.entity;

import java.time.Instant;
import java.util.UUID;

import com.tinniestudio.api.shared.entity.DomainEnums.*;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "upload_sessions")
@Getter
@Setter
@NoArgsConstructor
public class UploadSession extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private UploadType uploadType;

    @Enumerated(EnumType.STRING)
    private TargetEntityType targetEntityType;

    private UUID targetEntityId;

    @Column(nullable = false)
    private String storageKey;

    private String originalFilename;

    private String mimeType;

    private Long expectedMaxSizeBytes;

    @Enumerated(EnumType.STRING)
    private UploadStatus uploadStatus;

    private Instant expiresAt;

    private Instant completedAt;
}
