package com.lamastudio.backend.modules.auth.admin.repository;

import com.lamastudio.backend.modules.auth.admin.entity.Admin;
import com.lamastudio.backend.modules.auth.admin.entity.AdminRoleName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdminRepository extends JpaRepository<Admin, UUID> {

    Optional<Admin> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT COUNT(a) > 0 FROM Admin a JOIN a.roles r WHERE r = :role")
    boolean existsByRolesContaining(@Param("role") AdminRoleName role);

    @Query("SELECT a FROM Admin a JOIN a.roles r WHERE r = :role ORDER BY a.createdAt ASC")
    Optional<Admin> findFirstByRole(@Param("role") AdminRoleName role);

    Optional<Admin> findByPasswordResetToken(String token);
}
