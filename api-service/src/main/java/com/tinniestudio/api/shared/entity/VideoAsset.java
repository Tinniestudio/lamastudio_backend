package com.tinniestudio.api.shared.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.tinniestudio.api.shared.entity.DomainEnums.*;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "video_assets")
@Getter
@Setter
@NoArgsConstructor
public class VideoAsset extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  private Content content;

  @ManyToOne(fetch = FetchType.LAZY)
  private Season season;

  @ManyToOne(fetch = FetchType.LAZY)
  private Episode episode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private VideoAssetType assetType;

  @Column(nullable = false)
  private String originalFilename;

  @Enumerated(EnumType.STRING)
  private SourceFormat sourceFormat;

  @Column(name = "raw_storage_key", nullable = false)
  private String storageKey;

  private String manifestUrl;

  private Integer durationSeconds;

  private Integer width;

  private Integer height;

  private Long bitrate;

  private String codec;

  private Long fileSizeBytes;

  @Enumerated(EnumType.STRING)
  private ProcessingStatus processingStatus;

  // Written by media-worker (VideoProcessingService) on every processing attempt/failure —
  // present in the DB since V27/V29 but previously unmapped here, so api-service could never
  // read back why a video failed or how many retries occurred.
  @Column(columnDefinition = "TEXT")
  private String processingError;

  @Column(nullable = false)
  private int processingAttempts;

  @Column(nullable = false)
  private UUID uploadedBy;

  private UUID uploadSessionId;

  // Which of possibly-several VideoAssets sharing (target, assetType) is "the" one playback/
  // partner UI should treat as current, where target is the most specific of episode/season/
  // content set on this asset. Automatically flipped on successful processing
  // (NotificationConsumer) and manually flippable via PATCH /partners/videos/{id}/activate
  // (PartnerVideoController) — both retire every true sibling atomically via
  // VideoActivationService.activateAndRetireSiblings(...) before setting this one true.
  @Column(name = "is_active", nullable = false)
  private boolean isActive = false;

  @OneToMany(mappedBy = "videoAsset", cascade = CascadeType.ALL)
  private List<VideoVariant> variants = new ArrayList<>();

  @OneToMany(mappedBy = "videoAsset", cascade = CascadeType.ALL)
  private List<Subtitle> subtitles = new ArrayList<>();
}
