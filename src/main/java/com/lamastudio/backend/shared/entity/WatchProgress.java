package com.lamastudio.backend.shared.entity;

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

  private Boolean completed = false;

  private Instant lastWatchedAt;
}
