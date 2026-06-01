package com.lamastudio.backend.modules.auth.user.repository;

import com.lamastudio.backend.modules.auth.user.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    List<UserSession> findByUserIdAndRevokedFalse(UUID userId);

    long countByUserIdAndRevokedFalse(UUID userId);

    Optional<UserSession> findFirstByUserIdAndRevokedFalseOrderByCreatedAtAsc(UUID userId);

    Optional<UserSession> findByUserIdAndIdAndRevokedFalse(UUID userId, UUID sessionId);

    @Modifying
    @Query("UPDATE UserSession s SET s.revoked = true, s.revokedAt = CURRENT_TIMESTAMP, s.revokedByAdminId = :adminId WHERE s.userId = :userId AND s.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("adminId") UUID adminId);
}
