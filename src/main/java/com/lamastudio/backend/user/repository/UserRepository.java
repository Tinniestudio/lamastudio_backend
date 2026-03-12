package com.lamastudio.backend.user.repository;

import com.lamastudio.backend.user.entity.AuthProvider;
import com.lamastudio.backend.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
