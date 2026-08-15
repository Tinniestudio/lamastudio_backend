package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.UploadSession;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

    @Query("SELECT COALESCE(SUM(u.fileSizeBytes), 0) FROM UploadSession u")
    Long sumFileSizeBytes();

    Page<UploadSession> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Delete upload sessions that have passed their expiry time and were never completed.
     * Only the DB row is removed; the corresponding storage object is NOT deleted.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM UploadSession s WHERE s.expiresAt IS NOT NULL AND s.expiresAt < :now AND s.uploadStatus <> :completedStatus")
    int deleteExpiredNonCompleted(@Param("now") Instant now, @Param("completedStatus") UploadStatus completedStatus);
}
