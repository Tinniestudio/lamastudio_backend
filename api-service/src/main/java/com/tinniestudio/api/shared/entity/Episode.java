package com.tinniestudio.api.shared.entity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "episodes", uniqueConstraints = {
    @UniqueConstraint(columnNames = { "season_id", "episode_number" })
})
@Getter
@Setter
@NoArgsConstructor
public class Episode extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "season_id", nullable = false)
  private Season season;

  @Column(nullable = false)
  private Integer episodeNumber;

  /** Trigger-generated (V58, trg_episode_slug) — slugify(title), unique within the season. Never set from Java. */
  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Column(nullable = false, insertable = false, updatable = false)
  private String slug;

  @Column(nullable = false)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String description;

  private LocalDate releaseDate;

  private String thumbnailUrl;

  private Integer durationSeconds;

  @OneToMany(mappedBy = "episode", cascade = CascadeType.ALL)
  private List<VideoAsset> videoAssets = new ArrayList<>();
}
