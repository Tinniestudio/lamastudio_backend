package com.tinniestudio.api.modules.billing.repository;

import com.tinniestudio.api.shared.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    Optional<Coupon> findByCodeIgnoreCase(String code);

    /**
     * Atomically increments uses_count only if the coupon still has room under max_uses
     * (or has no cap at all). This closes the lost-update race in the old
     * read-check-in-Java-then-save pattern, where two concurrent redemptions could both
     * read uses_count < max_uses before either transaction committed, letting a coupon
     * capped at N uses be redeemed more than N times.
     *
     * Returns the number of rows affected: 1 = redemption accepted, 0 = limit reached
     * (redemption must be rejected by the caller).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Coupon c SET c.usesCount = c.usesCount + 1 " +
            "WHERE c.id = :id AND (c.maxUses IS NULL OR c.usesCount < c.maxUses)")
    int incrementUsesCountIfUnderLimit(@Param("id") UUID id);
}
