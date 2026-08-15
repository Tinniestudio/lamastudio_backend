package com.tinniestudio.api.modules.season.repository;

import com.tinniestudio.api.shared.entity.Season;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeasonRepository extends JpaRepository<Season, UUID> {
    @EntityGraph(attributePaths = "episodes")
    List<Season> findByContentIdOrderBySeasonNumberAsc(UUID contentId);
    boolean existsByContentIdAndSeasonNumber(UUID contentId, int seasonNumber);

    @Query("SELECT MAX(s.seasonNumber) FROM Season s WHERE s.content.id = :contentId")
    Optional<Integer> findMaxSeasonNumberByContentId(@Param("contentId") UUID contentId);
}
