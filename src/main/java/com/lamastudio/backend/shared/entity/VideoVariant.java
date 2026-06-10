package com.lamastudio.backend.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "video_variants")
@Getter
@Setter
@NoArgsConstructor
public class VideoVariant extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "video_asset_id", nullable = false)
  private VideoAsset videoAsset;

  private String resolution;

  private Integer width;

  private Integer height;

  private Long bitrate;

  private String storageKey;

  private String manifestUrl;
}
