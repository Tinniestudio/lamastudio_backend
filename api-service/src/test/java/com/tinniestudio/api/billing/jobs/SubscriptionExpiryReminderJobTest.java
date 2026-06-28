package com.tinniestudio.api.billing.jobs;

import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.shared.entity.SubscriptionPlan;
import com.tinniestudio.api.shared.entity.UserSubscription;
import com.tinniestudio.api.shared.jobs.SubscriptionExpiryReminderJob;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionExpiryReminderJob")
class SubscriptionExpiryReminderJobTest {

    @Mock private UserSubscriptionRepository subscriptionRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private CacheService cacheService;

    @InjectMocks private SubscriptionExpiryReminderJob job;

    @Test
    @DisplayName("non-auto-renew subscription expiring within 3 days triggers reminder email")
    void expiringManualSubscription_sendsReminder() {
        UUID userId = UUID.randomUUID();
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setName("SILVER");

        UserSubscription sub = new UserSubscription();
        sub.setUserId(userId);
        sub.setAutoRenew(false);
        sub.setPlan(plan);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setEndDate(Instant.now().plus(Duration.ofDays(2)));

        com.tinniestudio.api.shared.entity.User user = new com.tinniestudio.api.shared.entity.User();
        user.setEmail("user@example.com");
        user.setFirstName("Jane");

        when(cacheService.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(subscriptionRepository.findByStatusAndAutoRenewFalseAndEndDateBetween(
            eq(SubscriptionStatus.ACTIVE), any(), any())).thenReturn(List.of(sub));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        job.sendExpiryReminders();

        verify(emailService).sendSubscriptionExpiringEmail(
            eq("user@example.com"), eq("Jane"), anyString(), eq("SILVER"));
    }

    @Test
    @DisplayName("when lock is already held, job skips entirely")
    void lockHeld_skips() {
        when(cacheService.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        job.sendExpiryReminders();

        verifyNoInteractions(subscriptionRepository);
    }
}
