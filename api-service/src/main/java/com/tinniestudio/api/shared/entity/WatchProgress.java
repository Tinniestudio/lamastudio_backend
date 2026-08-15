package com.tinniestudio.api.shared.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "watch_progress")
@Getter
@Setter
@NoArgsConstructor
public class WatchProgress extends BaseEntity {

  @Column(nullable = false)
  private UUID userId;

  private UUID contentId;

  private UUID episodeId;

  private Integer progressSeconds;

  private Integer durationSeconds;

  @Column(nullable = false)
  private Boolean completed = false;

  private Instant lastWatchedAt;

  private UUID videoAssetId;

  @Column(precision = 5, scale = 2)
  private java.math.BigDecimal completionPercentage;

  private String deviceType;
}
