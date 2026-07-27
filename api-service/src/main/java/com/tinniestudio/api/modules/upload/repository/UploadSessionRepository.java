package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.UploadSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

    @Query("SELECT COALESCE(SUM(u.fileSizeBytes), 0) FROM UploadSession u")
    Long sumFileSizeBytes();

    Page<UploadSession> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
