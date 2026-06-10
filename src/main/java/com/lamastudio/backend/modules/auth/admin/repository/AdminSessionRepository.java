package com.lamastudio.backend.modules.auth.admin.repository;

import com.lamastudio.backend.modules.auth.admin.entity.AdminSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdminSessionRepository extends JpaRepository<AdminSession, UUID> {

    List<AdminSession> findByAdminIdAndRevokedFalse(UUID adminId);

    Optional<AdminSession> findByAdminIdAndIdAndRevokedFalse(UUID adminId, UUID sessionId);

    Optional<AdminSession> findFirstByAdminIdOrderByCreatedAtAsc(UUID adminId);
}
