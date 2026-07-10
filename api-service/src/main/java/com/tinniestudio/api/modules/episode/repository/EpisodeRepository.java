package com.tinniestudio.api.modules.episode.repository;

import com.tinniestudio.api.shared.entity.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EpisodeRepository extends JpaRepository<Episode, UUID> {
    List<Episode> findBySeasonIdOrderByEpisodeNumberAsc(UUID seasonId);
    boolean existsBySeasonIdAndEpisodeNumber(UUID seasonId, int episodeNumber);

    @Query("SELECT MAX(e.episodeNumber) FROM Episode e WHERE e.season.id = :seasonId")
    Optional<Integer> findMaxEpisodeNumberBySeasonId(@Param("seasonId") UUID seasonId);
}
