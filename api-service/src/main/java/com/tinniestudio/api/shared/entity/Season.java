package com.tinniestudio.api.shared.entity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "seasons", uniqueConstraints = {
    @UniqueConstraint(columnNames = { "content_id", "season_number" })
})
@Getter
@Setter
@NoArgsConstructor
public class Season extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "content_id", nullable = false)
  private Content content;

  @Column(nullable = false)
  private Integer seasonNumber;

  private String title;

  @Column(columnDefinition = "TEXT")
  private String description;

  private LocalDate releaseDate;

  private String posterUrl;

  private String thumbnailUrl;

  @OneToMany(mappedBy = "season", cascade = CascadeType.ALL)
  private List<Episode> episodes = new ArrayList<>();

  @OneToMany(mappedBy = "season", cascade = CascadeType.ALL)
  private List<VideoAsset> trailers = new ArrayList<>();
}
