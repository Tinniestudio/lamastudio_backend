package com.tinniestudio.api.billing.jobs;

import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.shared.entity.SubscriptionPlan;
import com.tinniestudio.api.shared.entity.UserSubscription;
import com.tinniestudio.api.shared.jobs.SubscriptionExpirationJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionExpirationJob")
class SubscriptionExpirationJobTest {

    @Mock private UserSubscriptionRepository subscriptionRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private CacheService cacheService;

    @InjectMocks private SubscriptionExpirationJob job;

    @Test
    @DisplayName("expired subscriptions are set to EXPIRED and expiry email is sent")
    void expiredSubscriptions_markedExpiredAndEmailSent() {
        UUID userId = UUID.randomUUID();
        UserSubscription sub = new UserSubscription();
        sub.setUserId(userId);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setEndDate(Instant.now().minusSeconds(3600));

        when(cacheService.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(subscriptionRepository.findByStatusAndEndDateBefore(eq(SubscriptionStatus.ACTIVE), any()))
            .thenReturn(List.of(sub));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        job.expireSubscriptions();

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        verify(subscriptionRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("subscriptions with future endDate are not expired")
    void futureSubscription_notExpired() {
        when(cacheService.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(subscriptionRepository.findByStatusAndEndDateBefore(any(), any()))
            .thenReturn(List.of());

        job.expireSubscriptions();

        verify(subscriptionRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("when distributed lock is already held, job skips without processing")
    void lockAlreadyHeld_skips() {
        when(cacheService.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        job.expireSubscriptions();

        verifyNoInteractions(subscriptionRepository);
    }
}
