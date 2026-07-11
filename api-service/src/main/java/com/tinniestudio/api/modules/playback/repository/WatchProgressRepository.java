package com.tinniestudio.api.modules.playback.repository;

import com.tinniestudio.api.shared.entity.WatchProgress;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WatchProgressRepository extends JpaRepository<WatchProgress, UUID> {

    @Query("SELECT w FROM WatchProgress w WHERE w.userId = :userId AND w.contentId = :contentId AND w.episodeId IS NULL")
    Optional<WatchProgress> findMovieProgress(@Param("userId") UUID userId, @Param("contentId") UUID contentId);

    Optional<WatchProgress> findByUserIdAndEpisodeId(UUID userId, UUID episodeId);

    List<WatchProgress> findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(UUID userId, Pageable pageable);
}
