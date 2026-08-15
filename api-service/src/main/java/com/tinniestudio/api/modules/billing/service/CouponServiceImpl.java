package com.tinniestudio.api.modules.billing.service;

import com.tinniestudio.api.modules.billing.dto.CouponValidationResult;
import com.tinniestudio.api.modules.billing.repository.CouponRepository;
import com.tinniestudio.api.modules.billing.repository.CouponRedemptionRepository;
import com.tinniestudio.api.shared.entity.Coupon;
import com.tinniestudio.api.shared.entity.CouponRedemption;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;

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
        if (!couponRepository.findById(couponId).isPresent()) {
            throw new ResourceNotFoundException("Coupon not found");
        }

        // Atomic conditional UPDATE instead of read-check-in-Java-then-save: this prevents
        // the lost-update race where two concurrent redemptions could both pass the
        // uses_count < max_uses check before either commit, over-redeeming a capped coupon.
        int rowsAffected = couponRepository.incrementUsesCountIfUnderLimit(couponId);
        if (rowsAffected == 0) {
            throw new BadRequestException("Coupon usage limit has been reached");
        }

        CouponRedemption redemption = new CouponRedemption();
        redemption.setCouponId(couponId);
        redemption.setUserId(userId);
        redemption.setSubscriptionId(subscriptionId);

        try {
            // saveAndFlush: see ContentService.create for why plain save() doesn't reliably
            // surface the constraint violation inside this try/catch.
            couponRedemptionRepository.saveAndFlush(redemption);
        } catch (DataIntegrityViolationException ex) {
            throw new BadRequestException("Coupon already redeemed by this user");
        }

        log.info("Coupon {} redeemed by user {} for subscription {}", couponId, userId, subscriptionId);
    }

    private CouponValidationResult invalid(String reason) {
        return CouponValidationResult.builder().valid(false).reason(reason).build();
    }
}
