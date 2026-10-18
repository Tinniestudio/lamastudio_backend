package com.tinniestudio.api.modules.billing.repository;

import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.shared.entity.UserSubscription;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, UUID> {

    Optional<UserSubscription> findByUserIdAndStatus(UUID userId, SubscriptionStatus status);

    Optional<UserSubscription> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

    java.util.List<UserSubscription> findByStatusAndEndDateBefore(SubscriptionStatus status, java.time.Instant date);

    java.util.List<UserSubscription> findByStatusAndAutoRenewFalseAndEndDateBetween(SubscriptionStatus status, java.time.Instant from, java.time.Instant to);

    long countByStatus(SubscriptionStatus status);

    java.util.List<UserSubscription> findByAutoRenewTrueAndNextRenewalAtLessThanEqual(Instant cutoff);

    Optional<UserSubscription> findByStripeCustomerId(String stripeCustomerId);

    /**
     * Caller must invoke this from within its own {@code @Transactional} method — the row lock
     * is held only for the lifetime of that transaction and is released as soon as it commits.
     * Calling this without an enclosing transaction acquires and releases the lock immediately,
     * silently defeating the race-safety this method exists for.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSubscription s where s.id = :id")
    Optional<UserSubscription> findByIdForUpdate(@Param("id") UUID id);
}
