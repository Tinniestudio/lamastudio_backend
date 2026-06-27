package com.tinniestudio.api.modules.billing.repository;

import com.tinniestudio.api.shared.entity.CouponRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

    boolean existsByCouponIdAndUserId(UUID couponId, UUID userId);
}
