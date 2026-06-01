package com.lamastudio.backend.modules.user.service;

import com.lamastudio.backend.modules.user.dto.AvatarConfirmRequest;
import com.lamastudio.backend.modules.user.dto.AvatarUpdateRequest;
import com.lamastudio.backend.modules.user.dto.AvatarUploadResponse;
import com.lamastudio.backend.modules.user.dto.UpdateNotificationRequest;
import com.lamastudio.backend.modules.user.dto.UpdatePasswordRequest;
import com.lamastudio.backend.modules.user.dto.UpdateProfileRequest;
import com.lamastudio.backend.modules.user.dto.UserProfileResponse;

import java.util.UUID;

public interface UserProfileService {

    UserProfileResponse getProfile(UUID userId);

    UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request);

    void updateNotifications(UUID userId, UpdateNotificationRequest request);

    AvatarUploadResponse initiateAvatarUpload(UUID userId, AvatarUpdateRequest request);

    String setAvatarByUrl(UUID userId, String avatarUrl);

    String confirmAvatarUpload(UUID userId, AvatarConfirmRequest request);

    void changePassword(UUID userId, UpdatePasswordRequest request, UUID currentSessionId);
}
