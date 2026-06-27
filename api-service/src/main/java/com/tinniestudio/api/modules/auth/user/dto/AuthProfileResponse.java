package com.tinniestudio.api.modules.auth.user.dto;

import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.UserSubscription;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Getter
@Builder
public class AuthProfileResponse {

        private UUID userId;
        private String email;
        private String firstName;
        private String lastName;
        private String displayName;
        private String avatarUrl;
        private Set<String> roles;
        private String provider;
        private boolean emailVerified;
        private String message;

        private SubscriptionDto subscription;

        private DevicesDto devices;

        @Getter
        @Builder
        public static class DevicesDto {
                private int active;
                private int max;
                private List<SessionDto> sessions;
        }

        public static AuthProfileResponse of(User user, UserSubscription sub,
                        List<SessionDto> sessions, UUID currentSessionId,
                        boolean canWatch,
                        String message) {
                Set<String> roles = user.getRoles().stream()
                                .map(r -> r.getName().name())
                                .collect(Collectors.toSet());

                // Mark current session
                List<SessionDto> markedSessions = sessions.stream()
                                .map(s -> SessionDto.builder()
                                                .sessionId(s.getSessionId())
                                                .deviceName(s.getDeviceName())
                                                .ipAddress(s.getIpAddress())
                                                .lastUsedAt(s.getLastUsedAt())
                                                .current(currentSessionId != null
                                                                && currentSessionId.equals(s.getSessionId()))
                                                .build())
                                .collect(Collectors.toList());

                SubscriptionDto subscriptionDto = null;
                if (sub != null && sub.getPlan() != null) {
                        int maxDevices = sub.getMaxDevices() != null ? sub.getMaxDevices() : 1;
                        subscriptionDto = SubscriptionDto.builder()
                                        .plan(sub.getPlan().getName())
                                        .status(sub.getStatus().name())
                                        .maxDevices(maxDevices)
                                        .contentWatchesUsed(sub.getContentWatchesUsed())
                                        .contentWatchesLimit(sub.getPlan().getContentLimit())
                                        .canWatch(canWatch)
                                        .expiresAt(sub.getEndDate())
                                        .build();
                }

                int maxDevices = (subscriptionDto != null) ? subscriptionDto.getMaxDevices() : 1;

                return AuthProfileResponse.builder()
                                .userId(user.getId())
                                .email(user.getEmail())
                                .firstName(user.getFirstName())
                                .lastName(user.getLastName())
                                .displayName(user.getDisplayName())
                                .avatarUrl(user.getAvatarUrl())
                                .roles(roles)
                                .provider(user.getProvider().name())
                                .emailVerified(user.isEmailVerified())
                                .subscription(subscriptionDto)
                                .devices(DevicesDto.builder()
                                                .active(markedSessions.size())
                                                .max(maxDevices)
                                                .sessions(markedSessions)
                                                .build())
                                .message(message)
                                .build();
        }
}
