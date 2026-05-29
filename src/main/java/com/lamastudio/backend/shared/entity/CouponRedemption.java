package com.lamastudio.backend.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "coupon_redemptions",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_coupon_user",
        columnNames = {"coupon_id", "user_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class CouponRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @CreationTimestamp
    @Column(name = "redeemed_at", nullable = false, updatable = false)
    private Instant redeemedAt;
}
