package com.lamastudio.backend.modules.auth.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "user_sessions",
    indexes = {
        @Index(name = "idx_user_sessions_user_id", columnList = "user_id"),
        @Index(name = "idx_user_sessions_active", columnList = "user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "refresh_token_hash", nullable = false)
    private String refreshTokenHash;

    @Column(name = "device_fingerprint", length = 64)
    private String deviceFingerprint;

    @Column(name = "device_name", length = 255)
    private String deviceName;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_admin_id")
    private UUID revokedByAdminId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public void revoke(UUID adminId) {
        this.revoked = true;
        this.revokedAt = Instant.now();
        this.revokedByAdminId = adminId;
    }
}
