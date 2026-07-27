package com.tinniestudio.api.modules.admin.dto;

import com.tinniestudio.api.shared.entity.User;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record AdminUserResponse(
    UUID id,
    String email,
    String accountStatus,
    Set<String> roles,
    boolean emailVerified,
    Instant createdAt
) {
    public static AdminUserResponse from(User u) {
        return new AdminUserResponse(
            u.getId(),
            u.getEmail(),
            u.getAccountStatus().name(),
            u.getRoles().stream().map(r -> r.getName().name()).collect(Collectors.toSet()),
            u.isEmailVerified(),
            u.getCreatedAt()
        );
    }
}
