package com.lamastudio.backend.modules.billing.repository;

import com.lamastudio.backend.shared.entity.CouponRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

    boolean existsByCouponIdAndUserId(UUID couponId, UUID userId);
}
