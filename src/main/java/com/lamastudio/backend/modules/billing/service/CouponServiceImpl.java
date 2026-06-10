package com.lamastudio.backend.modules.billing.service;

import com.lamastudio.backend.modules.billing.dto.CouponValidationResult;
import com.lamastudio.backend.modules.billing.repository.CouponRepository;
import com.lamastudio.backend.modules.billing.repository.CouponRedemptionRepository;
import com.lamastudio.backend.shared.entity.Coupon;
import com.lamastudio.backend.shared.entity.CouponRedemption;
import com.lamastudio.backend.shared.exception.BadRequestException;
import com.lamastudio.backend.shared.exception.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository couponRedemptionRepository;

    @Override
    @Transactional(readOnly = true)
    public CouponValidationResult validateCoupon(String code, UUID userId) {
        Coupon coupon = couponRepository.findByCodeIgnoreCase(code).orElse(null);

        if (coupon == null || !coupon.isActive()) {
            return invalid("not_found");
        }

        Instant now = Instant.now();
        if (coupon.getValidFrom() != null && now.isBefore(coupon.getValidFrom())) {
            return invalid("expired");
        }
        if (coupon.getValidUntil() != null && now.isAfter(coupon.getValidUntil())) {
            return invalid("expired");
        }

        if (coupon.getMaxUses() != null && coupon.getUsesCount() >= coupon.getMaxUses()) {
            return invalid("limit_reached");
        }

        if (couponRedemptionRepository.existsByCouponIdAndUserId(coupon.getId(), userId)) {
            return invalid("already_used");
        }

        return CouponValidationResult.builder()
                .valid(true)
                .couponId(coupon.getId())
                .discountType(coupon.getDiscountType())
                .discountValue(coupon.getDiscountValue())
                .currency(coupon.getCurrency())
                .build();
    }

    @Override
    @Transactional
    public void redeemCoupon(UUID couponId, UUID userId, UUID subscriptionId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));

        coupon.setUsesCount(coupon.getUsesCount() + 1);
        couponRepository.save(coupon);

        CouponRedemption redemption = new CouponRedemption();
        redemption.setCouponId(couponId);
        redemption.setUserId(userId);
        redemption.setSubscriptionId(subscriptionId);

        try {
            couponRedemptionRepository.save(redemption);
        } catch (DataIntegrityViolationException ex) {
            throw new BadRequestException("Coupon already redeemed by this user");
        }

        log.info("Coupon {} redeemed by user {} for subscription {}", couponId, userId, subscriptionId);
    }

    private CouponValidationResult invalid(String reason) {
        return CouponValidationResult.builder().valid(false).reason(reason).build();
    }
}
