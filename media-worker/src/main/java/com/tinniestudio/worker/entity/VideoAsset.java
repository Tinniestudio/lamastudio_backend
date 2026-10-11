package com.tinniestudio.worker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "video_assets")
@Getter @Setter @NoArgsConstructor
public class VideoAsset {

    @Id
    private UUID id;

    private UUID contentId;
    private UUID uploadSessionId;

    @Column(name = "raw_storage_key", nullable = false)
    private String rawStorageKey;

    private String originalFilename;

    @Column(name = "manifest_url")
    private String manifestUrl;

    private Integer durationSeconds;
    private Integer width;
    private Integer height;
    private Long bitrate;
    private String codec;
    private Long fileSizeBytes;

    // "MAIN_VIDEO" or "TRAILER" (api-service's VideoAssetType, stored as a plain string here
    // like processingStatus below) — read by VideoProcessingService to segregate processed
    // output under processed/main/ vs processed/trailers/, so CloudFront can restrict viewer
    // access to main-video output only while trailers stay publicly reachable.
    private String assetType;

    private String processingStatus;
    private String processingError;
    private int processingAttempts;

    private UUID uploadedBy;

    @UpdateTimestamp
    private Instant updatedAt;
}
