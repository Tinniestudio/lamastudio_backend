package com.tinniestudio.api.modules.user.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.AuthProvider;
import com.tinniestudio.api.shared.entity.User;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    /**
     * Scoped to exclude soft-deleted accounts: a soft-deleted user's email must not permanently
     * block that address from being used to register again. Used by AuthService.register for
     * the duplicate-email check.
     *
     * Deliberately NOT applied to {@link #findByEmail(String)} — that method is relied on by
     * login/resend-verification/forgot-password/OAuth2 linking to fetch a soft-deleted user so
     * they can be explicitly rejected via the existing isActive()/AccountNotActiveException path,
     * not silently treated as "no such user".
     */
    boolean existsByEmailAndDeletedAtIsNull(String email);

    @Query("SELECT u FROM User u WHERE u.emailVerificationToken = :token AND u.deletedAt IS NULL")
    Optional<User> findByEmailVerificationToken(@Param("token") String token);

    @Query("SELECT u FROM User u WHERE u.passwordResetToken = :token AND u.deletedAt IS NULL")
    Optional<User> findByPasswordResetToken(@Param("token") String token);

    Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
        SELECT u FROM User u
        WHERE (:status IS NULL OR u.accountStatus = :status)
        AND (:search IS NULL OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY u.createdAt DESC
        """)
    Page<User> findByFilters(
        @Param("status") AccountStatus status,
        @Param("search") String search,
        Pageable pageable
    );

    long countByCreatedAtAfter(Instant after);
}
