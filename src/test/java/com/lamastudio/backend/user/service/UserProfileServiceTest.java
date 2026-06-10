package com.lamastudio.backend.user.service;

import com.lamastudio.backend.modules.auth.service.EmailService;
import com.lamastudio.backend.modules.auth.user.service.SessionService;
import com.lamastudio.backend.modules.billing.repository.UserSubscriptionRepository;
import com.lamastudio.backend.modules.billing.service.CapabilityService;
import com.lamastudio.backend.modules.user.dto.*;
import com.lamastudio.backend.modules.user.repository.UserProfileRepository;
import com.lamastudio.backend.modules.user.service.UserProfileServiceImpl;
import com.lamastudio.backend.shared.config.StripeProperties;
import com.lamastudio.backend.shared.entity.DomainEnums.AuthProvider;
import com.lamastudio.backend.shared.entity.DomainEnums.SubscriptionStatus;
import com.lamastudio.backend.shared.entity.User;
import com.lamastudio.backend.shared.entity.UserProfile;
import com.lamastudio.backend.shared.exception.BadRequestException;
import com.lamastudio.backend.shared.exception.ResourceNotFoundException;
import com.lamastudio.backend.shared.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileService")
class UserProfileServiceTest {

    @Mock private com.lamastudio.backend.modules.user.repository.UserRepository userRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private SessionService sessionService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private StorageService storageService;
    @Mock private StripeProperties stripeProperties;
    @Mock private UserSubscriptionRepository userSubscriptionRepository;
    @Mock private CapabilityService capabilityService;

    @InjectMocks private UserProfileServiceImpl service;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setEmail("test@example.com");
        user.setProvider(AuthProvider.LOCAL);
        user.setPasswordHash("$2a$12$encoded");
    }

    @Nested
    @DisplayName("getProfile()")
    class GetProfile {

        @Test
        @DisplayName("user with existing UserProfile returns merged response")
        void withProfile_returnsMergedResponse() {
            UserProfile profile = new UserProfile();
            profile.setUserId(userId);
            profile.setBio("A movie lover");
            profile.setLanguageCode("en");

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
            when(userSubscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(sessionService.getActiveSessions(userId)).thenReturn(List.of());
            when(capabilityService.canWatch(userId)).thenReturn(false);

            UserProfileResponse response = service.getProfile(userId);

            assertThat(response.getEmail()).isEqualTo("test@example.com");
            assertThat(response.getBio()).isEqualTo("A movie lover");
            assertThat(response.getLanguageCode()).isEqualTo("en");
        }

        @Test
        @DisplayName("user with no UserProfile returns defaults with null optional fields")
        void withoutProfile_returnsDefaults() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
            when(userSubscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(sessionService.getActiveSessions(userId)).thenReturn(List.of());
            when(capabilityService.canWatch(userId)).thenReturn(false);

            UserProfileResponse response = service.getProfile(userId);

            assertThat(response.getBio()).isNull();
            assertThat(response.isNotificationEmail()).isTrue();
        }

        @Test
        @DisplayName("unknown userId throws ResourceNotFoundException")
        void unknownUser_throws() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getProfile(userId))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("updateProfile()")
    class UpdateProfile {

        @Test
        @DisplayName("only provided fields change, others are unchanged")
        void partialUpdate_onlyChangesProvidedFields() {
            user.setFirstName("Original");
            UserProfile profile = new UserProfile();
            profile.setUserId(userId);
            profile.setBio("Old bio");

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(capabilityService.canWatch(userId)).thenReturn(false);

            UpdateProfileRequest request = new UpdateProfileRequest();
            request.setBio("New bio");

            UserProfileResponse response = service.updateProfile(userId, request);

            assertThat(response.getBio()).isEqualTo("New bio");
            assertThat(user.getFirstName()).isEqualTo("Original");
        }

        @Test
        @DisplayName("invalid timezone throws BadRequestException")
        void invalidTimezone_throws() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

            UpdateProfileRequest request = new UpdateProfileRequest();
            request.setTimezone("Not/AReal/Timezone");

            assertThatThrownBy(() -> service.updateProfile(userId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid timezone");
        }
    }

    @Nested
    @DisplayName("initiateAvatarUpload()")
    class AvatarUpload {

        @Test
        @DisplayName("unsupported MIME type throws BadRequestException")
        void unsupportedMime_throws() {
            AvatarUpdateRequest request = new AvatarUpdateRequest();
            request.setMode(AvatarUpdateRequest.Mode.UPLOAD);
            request.setMimeType("application/pdf");
            request.setFileSizeBytes(1024L);

            assertThatThrownBy(() -> service.initiateAvatarUpload(userId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("File type not supported");
        }

        @Test
        @DisplayName("file exceeding 10 MB throws BadRequestException")
        void oversizedFile_throws() {
            AvatarUpdateRequest request = new AvatarUpdateRequest();
            request.setMode(AvatarUpdateRequest.Mode.UPLOAD);
            request.setMimeType("image/jpeg");
            request.setFileSizeBytes(11_000_000L);

            assertThatThrownBy(() -> service.initiateAvatarUpload(userId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10 MB");
        }
    }

    @Nested
    @DisplayName("setAvatarByUrl()")
    class AvatarUrl {

        @Test
        @DisplayName("valid HTTPS URL updates avatarUrl")
        void validUrl_updates() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            String result = service.setAvatarByUrl(userId, "https://example.com/photo.jpg");
            assertThat(result).isEqualTo("https://example.com/photo.jpg");
        }

        @Test
        @DisplayName("HTTP (non-HTTPS) URL throws BadRequestException")
        void httpUrl_throws() {
            assertThatThrownBy(() -> service.setAvatarByUrl(userId, "http://example.com/photo.jpg"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("HTTPS");
        }
    }

    @Nested
    @DisplayName("changePassword()")
    class ChangePassword {

        @Test
        @DisplayName("correct password changes hash and revokes other sessions")
        void correctPassword_changesAndRevokes() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPass1!", "$2a$12$encoded")).thenReturn(true);
            when(passwordEncoder.encode("NewPass2!")).thenReturn("$2a$12$newencoded");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(sessionService).revokeAllExcept(any(), any());

            UpdatePasswordRequest req = new UpdatePasswordRequest();
            req.setCurrentPassword("OldPass1!");
            req.setNewPassword("NewPass2!");

            UUID sessionId = UUID.randomUUID();
            service.changePassword(userId, req, sessionId);

            assertThat(user.getPasswordHash()).isEqualTo("$2a$12$newencoded");
            verify(sessionService).revokeAllExcept(userId, sessionId);
        }

        @Test
        @DisplayName("wrong current password throws BadRequestException without changing hash")
        void wrongCurrentPassword_throws() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(false);

            UpdatePasswordRequest req = new UpdatePasswordRequest();
            req.setCurrentPassword("WrongPass1!");
            req.setNewPassword("NewPass2!");

            assertThatThrownBy(() -> service.changePassword(userId, req, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("incorrect");
            assertThat(user.getPasswordHash()).isEqualTo("$2a$12$encoded");
        }

        @Test
        @DisplayName("OAuth account throws BadRequestException with social login message")
        void oauthAccount_throws() {
            user.setProvider(AuthProvider.GOOGLE);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            UpdatePasswordRequest req = new UpdatePasswordRequest();
            req.setCurrentPassword("any");
            req.setNewPassword("NewPass2!");

            assertThatThrownBy(() -> service.changePassword(userId, req, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("login");
        }
    }
}
