package com.lamastudio.backend.modules.auth.user.service;

import com.lamastudio.backend.modules.auth.user.dto.AuthProfileResponse;
import com.lamastudio.backend.modules.auth.user.dto.SessionDto;
import com.lamastudio.backend.modules.billing.repository.UserSubscriptionRepository;
import com.lamastudio.backend.modules.billing.service.CapabilityService;
import com.lamastudio.backend.modules.user.repository.UserRepository;
import com.lamastudio.backend.shared.entity.DomainEnums.SubscriptionStatus;
import com.lamastudio.backend.shared.entity.User;
import com.lamastudio.backend.shared.entity.UserSubscription;
import com.lamastudio.backend.shared.exception.ResourceNotFoundException;

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
    public AuthProfileResponse getProfile(UUID userId, UUID currentSessionId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        UserSubscription sub = userSubscriptionRepository
                .findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .orElse(null);

        List<SessionDto> sessions = sessionService.getActiveSessions(userId);
        boolean canWatch = capabilityService.canWatch(userId);

        return AuthProfileResponse.of(user, sub, sessions, currentSessionId, canWatch);
    }
}
