package com.tinniestudio.api.modules.auth.user.repository;

import com.tinniestudio.api.modules.auth.user.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    List<UserSession> findByUserIdAndRevokedFalse(UUID userId);

    long countByUserIdAndRevokedFalse(UUID userId);

    Optional<UserSession> findFirstByUserIdAndRevokedFalseOrderByCreatedAtAsc(UUID userId);

    Optional<UserSession> findByUserIdAndIdAndRevokedFalse(UUID userId, UUID sessionId);

    /**
     * Lean existence check used on every authenticated request (JwtAuthenticationFilter) to
     * confirm the session tied to an access token hasn't been revoked. Deliberately a boolean
     * projection rather than fetching the whole entity.
     */
    boolean existsByUserIdAndIdAndRevokedFalse(UUID userId, UUID sessionId);

    @Modifying
    @Query("UPDATE UserSession s SET s.revoked = true, s.revokedAt = CURRENT_TIMESTAMP, s.revokedByAdminId = :adminId WHERE s.userId = :userId AND s.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("adminId") UUID adminId);

    /**
     * Delete sessions whose expiry time has passed.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.expiresAt < :now")
    int deleteExpiredSessions(@Param("now") Instant now);
}
