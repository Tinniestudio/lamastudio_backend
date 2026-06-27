package com.tinniestudio.api.modules.auth.user.service;

import com.tinniestudio.api.modules.auth.user.dto.AuthProfileResponse;
import com.tinniestudio.api.modules.auth.user.dto.SessionDto;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.billing.service.CapabilityService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.UserSubscription;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthProfileService {

    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final SessionService sessionService;
    private final CapabilityService capabilityService;

    @Transactional(readOnly = true)
    public AuthProfileResponse getProfile(UUID userId, UUID currentSessionId, String message) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        UserSubscription sub = userSubscriptionRepository
                .findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .orElse(null);

        List<SessionDto> sessions = sessionService.getActiveSessions(userId);
        boolean canWatch = capabilityService.canWatch(userId);

        return AuthProfileResponse.of(user, sub, sessions, currentSessionId, canWatch, message);
    }
}
