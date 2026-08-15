package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "watch_history")
@Getter
@Setter
@NoArgsConstructor
public class WatchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "episode_id")
    private UUID episodeId;

    @Column(name = "watched_at", nullable = false)
    private Instant watchedAt = Instant.now();

    @Column(name = "progress_seconds")
    private Integer progressSeconds;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
