package com.lamastudio.backend.role.repository;

import com.lamastudio.backend.role.entity.Role;
import com.lamastudio.backend.role.entity.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(RoleName name);
}
