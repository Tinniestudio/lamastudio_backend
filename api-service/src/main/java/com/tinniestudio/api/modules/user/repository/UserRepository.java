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

    boolean existsByEmail(String email);

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
