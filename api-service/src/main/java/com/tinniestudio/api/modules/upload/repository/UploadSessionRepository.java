package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.UploadSession;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.TargetEntityType;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

    @Query("SELECT COALESCE(SUM(u.fileSizeBytes), 0) FROM UploadSession u")
    Long sumFileSizeBytes();

    Page<UploadSession> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * The resume-check query: does this user already have a non-expired, still-PENDING upload
     * for this exact (targetEntityType, targetEntityId, uploadType)? Most recent first — if
     * somehow more than one exists (shouldn't normally happen), the caller only wants the latest.
     */
    @Query("SELECT s FROM UploadSession s WHERE s.userId = :userId AND s.targetEntityType = :targetEntityType " +
           "AND s.targetEntityId = :targetEntityId AND s.uploadType = :uploadType " +
           "AND s.uploadStatus = com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus.PENDING " +
           "AND s.expiresAt > :now ORDER BY s.createdAt DESC")
    List<UploadSession> findActiveSessions(
        @Param("userId") UUID userId,
        @Param("targetEntityType") TargetEntityType targetEntityType,
        @Param("targetEntityId") UUID targetEntityId,
        @Param("uploadType") UploadType uploadType,
        @Param("now") Instant now);

    /**
     * Delete upload sessions that have passed their expiry time and were never completed.
     * Only the DB row is removed; the corresponding storage object is NOT deleted.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM UploadSession s WHERE s.expiresAt IS NOT NULL AND s.expiresAt < :now AND s.uploadStatus <> :completedStatus")
    int deleteExpiredNonCompleted(@Param("now") Instant now, @Param("completedStatus") UploadStatus completedStatus);
}
