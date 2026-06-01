package com.lamastudio.backend.modules.billing.repository;

import com.lamastudio.backend.shared.entity.DomainEnums.SubscriptionStatus;
import com.lamastudio.backend.shared.entity.UserSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, UUID> {

    Optional<UserSubscription> findByUserIdAndStatus(UUID userId, SubscriptionStatus status);

    Optional<UserSubscription> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

    java.util.List<UserSubscription> findByStatusAndEndDateBefore(SubscriptionStatus status, java.time.Instant date);

    java.util.List<UserSubscription> findByStatusAndAutoRenewFalseAndEndDateBetween(SubscriptionStatus status, java.time.Instant from, java.time.Instant to);
}
