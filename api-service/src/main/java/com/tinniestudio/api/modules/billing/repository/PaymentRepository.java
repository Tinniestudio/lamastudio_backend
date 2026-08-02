package com.tinniestudio.api.modules.billing.repository;

import com.tinniestudio.api.shared.entity.DomainEnums.PaymentStatus;
import com.tinniestudio.api.shared.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByProviderReference(String providerReference);

    Optional<Payment> findByProviderReferenceAndUserId(String providerReference, UUID userId);

    Optional<Payment> findByIdAndUserId(UUID id, UUID userId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByProviderReferenceAndStatus(String providerReference, PaymentStatus status);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.createdAt >= :after AND p.status = 'SUCCESSFUL'")
    BigDecimal sumAmountAfter(@Param("after") Instant after);
}
