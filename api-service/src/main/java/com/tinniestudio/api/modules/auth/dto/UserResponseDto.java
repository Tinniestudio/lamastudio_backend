package com.tinniestudio.api.modules.auth.dto;

import java.util.Set;
import java.util.stream.Collectors;

import com.tinniestudio.api.shared.entity.User;

public record UserResponseDto(
        String userId,
        String email,
        String firstName,
        String lastName,
        String displayName,
        String avatarUrl,
        Set<String> roles,
        String provider,
        boolean emailVerified
) {
    public static UserResponseDto from(User u) {
        Set<String> roles = u.getRoles().stream()
                .map(r -> r.getName().name())
                .collect(Collectors.toSet());

        return new UserResponseDto(
                u.getId().toString(),
                u.getEmail(),
                u.getFirstName(),
                u.getLastName(),
                u.getDisplayName(),
                u.getAvatarUrl(),
                roles,
                u.getProvider().name(),
                u.isEmailVerified()
        );
    }
}
