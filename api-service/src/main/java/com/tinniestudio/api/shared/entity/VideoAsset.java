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

  @Column(nullable = false)
  private UUID uploadedBy;

  private UUID uploadSessionId;

  @OneToMany(mappedBy = "videoAsset", cascade = CascadeType.ALL)
  private List<VideoVariant> variants = new ArrayList<>();

  @OneToMany(mappedBy = "videoAsset", cascade = CascadeType.ALL)
  private List<Subtitle> subtitles = new ArrayList<>();
}
