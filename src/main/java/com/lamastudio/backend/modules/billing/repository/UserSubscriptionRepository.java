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
}
