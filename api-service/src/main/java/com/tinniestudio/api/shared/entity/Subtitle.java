package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.tinniestudio.api.shared.entity.DomainEnums.SubtitleFormat;

@Entity
@Table(name = "subtitles")
@Getter
@Setter
@NoArgsConstructor
public class Subtitle extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "video_asset_id", nullable = false)
  private VideoAsset videoAsset;

  @Column(nullable = false)
  private String languageCode;

  private String label;

  @Column(nullable = false)
  private String fileUrl;

  @Enumerated(EnumType.STRING)
  private SubtitleFormat format;

  private Boolean isDefault = false;
}
