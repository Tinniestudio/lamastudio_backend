package com.tinniestudio.api.modules.appeal.repository;

import com.tinniestudio.api.shared.entity.AccountAppeal;
import com.tinniestudio.api.shared.entity.DomainEnums.AppealStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface AccountAppealRepository extends JpaRepository<AccountAppeal, UUID> {
    Page<AccountAppeal> findByStatusOrderByCreatedAtDesc(AppealStatus status, Pageable pageable);
    Page<AccountAppeal> findAllByOrderByCreatedAtDesc(Pageable pageable);
    boolean existsByUserIdAndStatus(UUID userId, AppealStatus status);
}
