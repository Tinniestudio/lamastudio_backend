package com.tinniestudio.api.modules.partner.repository;

import com.tinniestudio.api.shared.entity.PartnerApplication;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PartnerApplicationRepository extends JpaRepository<PartnerApplication, UUID> {
    Page<PartnerApplication> findByStatusOrderByCreatedAtDesc(PartnerApplicationStatus status, Pageable pageable);
    Page<PartnerApplication> findAllByOrderByCreatedAtDesc(Pageable pageable);
    boolean existsByUserIdAndStatus(UUID userId, PartnerApplicationStatus status);
    Optional<PartnerApplication> findByUserId(UUID userId);
}
