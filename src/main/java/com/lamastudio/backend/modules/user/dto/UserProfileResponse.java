package com.lamastudio.backend.modules.user.dto;

import com.lamastudio.backend.modules.auth.user.dto.SessionDto;
import com.lamastudio.backend.shared.entity.DomainEnums.SubscriptionStatus;
import com.lamastudio.backend.shared.entity.User;
import com.lamastudio.backend.shared.entity.UserProfile;
import com.lamastudio.backend.shared.entity.UserSubscription;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Getter
@Builder
public class UserProfileResponse {

    @Getter
    @Builder
    public static class SubscriptionSummary {
        private UUID subscriptionId;
        private String planName;
        private SubscriptionStatus status;
        private Instant startDate;
        private Instant endDate;
        private boolean autoRenew;

        static SubscriptionSummary from(UserSubscription sub) {
            return SubscriptionSummary.builder()
                    .subscriptionId(sub.getId())
                    .planName(sub.getPlan() != null ? sub.getPlan().getName() : null)
                    .status(sub.getStatus())
                    .startDate(sub.getStartDate())
                    .endDate(sub.getEndDate())
                    .autoRenew(Boolean.TRUE.equals(sub.getAutoRenew()))
                    .build();
        }
    }

    private UUID userId;
    private String email;
    private String firstName;
    private String lastName;
    private String displayName;
    private String avatarUrl;
    private String phoneNumber;
    private LocalDate dateOfBirth;
    private String provider;
    private boolean emailVerified;
    private String bio;
    private String languageCode;
    private String countryCode;
    private String timezone;
    private boolean notificationEmail;
    private Set<String> roles;
    private Instant createdAt;
    private SubscriptionSummary subscription;
    private List<SessionDto> sessions;
    private Boolean canWatch;

    public static UserProfileResponse from(User user, UserProfile profile, UserSubscription sub, List<SessionDto> sessions, boolean canWatch) {
        return UserProfileResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .displayName(user.getDisplayName())
                .avatarUrl(user.getAvatarUrl())
                .phoneNumber(user.getPhoneNumber())
                .dateOfBirth(user.getDateOfBirth())
                .provider(user.getProvider().name())
                .emailVerified(user.isEmailVerified())
                .bio(profile != null ? profile.getBio() : null)
                .languageCode(profile != null ? profile.getLanguageCode() : "en")
                .countryCode(profile != null ? profile.getCountryCode() : null)
                .timezone(profile != null ? profile.getTimezone() : null)
                .notificationEmail(profile == null || profile.isNotificationEmail())
                .roles(user.getRoles().stream().map(r -> r.getName().name()).collect(Collectors.toSet()))
                .createdAt(user.getCreatedAt())
                .subscription(sub != null ? SubscriptionSummary.from(sub) : null)
                .sessions(sessions)
                .canWatch(canWatch)
                .build();
    }
}
