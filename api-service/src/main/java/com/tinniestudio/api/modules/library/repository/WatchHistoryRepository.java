package com.tinniestudio.api.modules.library.repository;

import com.tinniestudio.api.shared.entity.WatchHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WatchHistoryRepository extends JpaRepository<WatchHistory, UUID> {
    Page<WatchHistory> findByUserIdOrderByWatchedAtDesc(UUID userId, Pageable pageable);
    Optional<WatchHistory> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM WatchHistory w WHERE w.userId = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}
