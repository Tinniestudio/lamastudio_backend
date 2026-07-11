package com.tinniestudio.worker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processing_jobs")
@Getter @Setter @NoArgsConstructor
public class ProcessingJob {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "video_asset_id", nullable = false)
    private UUID videoAssetId;

    @Column(nullable = false, unique = true)
    private String jobId;

    @Column(nullable = false)
    private String status;

    private Instant stageStartedAt;
    private Instant completedAt;
    private String errorMessage;
    private int attempt;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
