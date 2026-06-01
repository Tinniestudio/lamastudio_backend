package com.lamastudio.backend.modules.billing.repository;

import com.lamastudio.backend.shared.entity.DomainEnums.PaymentStatus;
import com.lamastudio.backend.shared.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByProviderReference(String providerReference);

    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByProviderReferenceAndStatus(String providerReference, PaymentStatus status);
}
