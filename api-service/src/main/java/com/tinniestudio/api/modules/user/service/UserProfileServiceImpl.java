package com.tinniestudio.api.modules.user.service;

import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.modules.auth.user.dto.SessionDto;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.billing.service.CapabilityService;
import com.tinniestudio.api.modules.user.dto.AvatarConfirmRequest;
import com.tinniestudio.api.modules.user.dto.AvatarUpdateRequest;
import com.tinniestudio.api.modules.user.dto.AvatarUploadResponse;
import com.tinniestudio.api.modules.user.dto.UpdateNotificationRequest;
import com.tinniestudio.api.modules.user.dto.UpdatePasswordRequest;
import com.tinniestudio.api.modules.user.dto.UpdateProfileRequest;
import com.tinniestudio.api.modules.user.dto.UserProfileResponse;
import com.tinniestudio.api.modules.user.repository.UserProfileRepository;
import com.tinniestudio.api.shared.config.StripeProperties;
import com.tinniestudio.api.shared.entity.DomainEnums.AuthProvider;
import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.UserProfile;
import com.tinniestudio.api.shared.entity.UserSubscription;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import com.tinniestudio.api.shared.storage.PresignedUploadResult;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private static final long MAX_AVATAR_BYTES = 10_485_760L; // 10 MB
    private static final Set<String> ALLOWED_IMAGE_MIME_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp"
    );

    private final com.tinniestudio.api.modules.user.repository.UserRepository userRepository;
    // Note: using FQN to avoid conflict with auth module's UserRepository
    private final UserProfileRepository userProfileRepository;
    private final SessionService sessionService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final StorageService storageService;
    private final StripeProperties stripeProperties;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final CapabilityService capabilityService;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = loadUser(userId);
        UserSubscription sub = userSubscriptionRepository
                .findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .orElse(null);
        List<SessionDto> sessions = sessionService.getActiveSessions(userId);
        boolean canWatch = capabilityService.canWatch(userId);
        UserProfile profile = userProfileRepository.findByUserId(userId)
            .orElseGet(() -> createEmptyProfile(user));
        return UserProfileResponse.from(user, profile, sub, sessions, canWatch);
    }

    @Override
    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = loadUser(userId);
        UserProfile profile = userProfileRepository.findByUserId(userId)
            .orElseGet(() -> createEmptyProfile(user));

        if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (request.getLastName() != null)  user.setLastName(request.getLastName());
        if (request.getDisplayName() != null) user.setDisplayName(request.getDisplayName());
        if (request.getPhoneNumber() != null) user.setPhoneNumber(request.getPhoneNumber());
        if (request.getDateOfBirth() != null) user.setDateOfBirth(request.getDateOfBirth());

        if (request.getBio() != null)          profile.setBio(request.getBio());
        if (request.getLanguageCode() != null) profile.setLanguageCode(request.getLanguageCode());
        if (request.getCountryCode() != null)  profile.setCountryCode(request.getCountryCode());
        if (request.getTimezone() != null) {
            validateTimezone(request.getTimezone());
            profile.setTimezone(request.getTimezone());
        }

        userRepository.save(user);
        userProfileRepository.save(profile);
        return UserProfileResponse.from(user, profile, null, null, capabilityService.canWatch(userId));
    }

    @Override
    @Transactional
    public void updateNotifications(UUID userId, UpdateNotificationRequest request) {
        User user = loadUser(userId);
        UserProfile profile = userProfileRepository.findByUserId(userId)
            .orElseGet(() -> createEmptyProfile(user));
        profile.setNotificationEmail(request.getNotificationEmail());
        userProfileRepository.save(profile);
    }

    @Override
    @Transactional
    public AvatarUploadResponse initiateAvatarUpload(UUID userId, AvatarUpdateRequest request) {
        if (request.getMimeType() == null || !ALLOWED_IMAGE_MIME_TYPES.contains(request.getMimeType())) {
            throw new BadRequestException("File type not supported. Allowed: image/jpeg, image/png, image/webp");
        }
        if (request.getFileSizeBytes() == null || request.getFileSizeBytes() > MAX_AVATAR_BYTES) {
            throw new BadRequestException("File size exceeds the 10 MB limit");
        }

        String ext = mimeToExtension(request.getMimeType());
        String storageKey = "avatars/" + userId + "/" + UUID.randomUUID() + "." + ext;

        PresignedUploadResult result = storageService.generateUploadUrl(
            storageKey, request.getMimeType(), request.getFileSizeBytes(), Duration.ofMinutes(15)
        );
        return new AvatarUploadResponse(result.uploadUrl(), result.storageKey(), result.expiresAt());
    }

    @Override
    @Transactional
    public String setAvatarByUrl(UUID userId, String avatarUrl) {
        validateAvatarUrl(avatarUrl);
        User user = loadUser(userId);
        user.setAvatarUrl(avatarUrl);
        userRepository.save(user);
        return avatarUrl;
    }

    @Override
    @Transactional
    public String confirmAvatarUpload(UUID userId, AvatarConfirmRequest request) {
        if (!storageService.objectExists(request.getStorageKey())) {
            throw new BadRequestException("Upload not found in storage. Please upload the file before confirming.");
        }
        String cdnUrl = stripeProperties.getCdnBaseUrl() + "/" + request.getStorageKey();
        User user = loadUser(userId);
        user.setAvatarUrl(cdnUrl);
        userRepository.save(user);
        return cdnUrl;
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, UpdatePasswordRequest request, UUID currentSessionId) {
        User user = loadUser(userId);

        if (user.getProvider() != AuthProvider.LOCAL) {
            throw new BadRequestException("This account uses " + user.getProvider().name() + " login. Password change is not available.");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        sessionService.revokeAllExcept(userId, currentSessionId);

        try {
            emailService.sendPasswordChangedEmail(user.getEmail(),
                user.getFirstName() != null ? user.getFirstName() : user.getEmail());
        } catch (Exception ex) {
            log.warn("Failed to send password-changed email to {}: {}", user.getEmail(), ex.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User loadUser(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private UserProfile createEmptyProfile(User user) {
        UserProfile profile = new UserProfile();
        profile.setUser(user);
        return profile;
    }

    private void validateTimezone(String tz) {
        try {
            java.time.ZoneId.of(tz);
        } catch (java.time.DateTimeException ex) {
            throw new BadRequestException("Invalid timezone: " + tz);
        }
    }

    private void validateAvatarUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BadRequestException("avatarUrl is required");
        }
        if (url.length() > 2048) {
            throw new BadRequestException("avatarUrl must not exceed 2048 characters");
        }
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new BadRequestException("avatarUrl must use HTTPS");
            }
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("avatarUrl is not a valid URL");
        }
    }

    private String mimeToExtension(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png"  -> "png";
            case "image/webp" -> "webp";
            default           -> "jpg";
        };
    }
}
