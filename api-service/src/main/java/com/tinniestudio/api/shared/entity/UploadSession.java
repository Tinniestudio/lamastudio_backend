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

    @Column(columnDefinition = "TEXT")
    private String presignedUrl;

    private String originalFilename;

    private String mimeType;

    private Long expectedMaxSizeBytes;

    // Both null for a non-multipart session (everything except RAW_VIDEO/TRAILER — see
    // UploadService.createSession()). multipartUploadId is S3's own upload id, the handle every
    // part-URL/list/complete call must reference.
    private String multipartUploadId;

    private Long partSizeBytes;

    @Enumerated(EnumType.STRING)
    private UploadStatus uploadStatus;

    private Instant expiresAt;

    private Instant completedAt;

    // NOT NULL DEFAULT 0 at the DB level (V38), populated with the real size once the upload
    // is confirmed in completeSession(). Without a Java-side default, Hibernate sends an
    // explicit NULL on the initial INSERT (session creation) instead of omitting the column,
    // which bypasses the DB default and violates the NOT NULL constraint.
    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes = 0L;

    @Version
    private Long version;
}
