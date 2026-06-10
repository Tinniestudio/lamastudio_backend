package com.lamastudio.backend.shared.jobs;

import com.lamastudio.backend.modules.auth.service.EmailService;
import com.lamastudio.backend.modules.billing.repository.UserSubscriptionRepository;
import com.lamastudio.backend.modules.user.repository.UserRepository;
import com.lamastudio.backend.shared.cache.CacheService;
import com.lamastudio.backend.shared.entity.DomainEnums.SubscriptionStatus;
import com.lamastudio.backend.shared.entity.UserSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryReminderJob {

    private static final String LOCK_KEY = "tinnie:lock:subscription-expiry-reminder";
    private static final Duration LOCK_TTL = Duration.ofHours(2);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneOffset.UTC);

    private final UserSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final CacheService cacheService;

    @Scheduled(cron = "0 0 8 * * *")
    public void sendExpiryReminders() {
        if (!cacheService.setIfAbsent(LOCK_KEY, "locked", LOCK_TTL)) {
            log.debug("SubscriptionExpiryReminderJob: lock held by another instance, skipping");
            return;
        }
        Instant start = Instant.now();
        try {
            Instant now = Instant.now();
            Instant in3Days = now.plus(Duration.ofDays(3));

            List<UserSubscription> expiring = subscriptionRepository
                .findByStatusAndAutoRenewFalseAndEndDateBetween(SubscriptionStatus.ACTIVE, now, in3Days);

            for (UserSubscription sub : expiring) {
                sendReminderEmail(sub);
            }
            if (!expiring.isEmpty()) {
                log.info("SubscriptionExpiryReminderJob: sent {} reminder emails in {}ms",
                    expiring.size(), Duration.between(start, Instant.now()).toMillis());
            }
        } finally {
            cacheService.delete(LOCK_KEY);
        }
    }

    private void sendReminderEmail(UserSubscription sub) {
        userRepository.findById(sub.getUserId()).ifPresent(user -> {
            try {
                String planName = sub.getPlan() != null ? sub.getPlan().getName() : "your plan";
                String endDate = sub.getEndDate() != null ? DATE_FMT.format(sub.getEndDate()) : "soon";
                String name = user.getFirstName() != null ? user.getFirstName() : user.getEmail();
                emailService.sendSubscriptionExpiringEmail(user.getEmail(), name, endDate, planName);
            } catch (Exception ex) {
                log.warn("Failed to send expiry reminder email to user {}: {}", sub.getUserId(), ex.getMessage());
            }
        });
    }
}
