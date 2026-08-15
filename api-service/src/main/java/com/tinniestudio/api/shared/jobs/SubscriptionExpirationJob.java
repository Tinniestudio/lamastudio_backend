package com.tinniestudio.api.shared.jobs;

import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.shared.entity.UserSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpirationJob {

    private static final String LOCK_KEY = "tinnie:lock:subscription-expiration";
    private static final Duration LOCK_TTL = Duration.ofHours(2);

    private final UserSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final CacheService cacheService;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void expireSubscriptions() {
        if (!cacheService.setIfAbsent(LOCK_KEY, "locked", LOCK_TTL)) {
            log.debug("SubscriptionExpirationJob: lock held by another instance, skipping");
            return;
        }
        Instant start = Instant.now();
        try {
            List<UserSubscription> expired = subscriptionRepository
                .findByStatusAndEndDateBefore(SubscriptionStatus.ACTIVE, Instant.now());

            for (UserSubscription sub : expired) {
                sub.setStatus(SubscriptionStatus.EXPIRED);
                sendExpiryEmail(sub);
            }
            if (!expired.isEmpty()) {
                subscriptionRepository.saveAll(expired);
                log.info("SubscriptionExpirationJob: expired {} subscriptions in {}ms",
                    expired.size(), Duration.between(start, Instant.now()).toMillis());
            }
        } finally {
            cacheService.delete(LOCK_KEY);
        }
    }

    private void sendExpiryEmail(UserSubscription sub) {
        userRepository.findById(sub.getUserId()).ifPresent(user -> {
            try {
                emailService.sendSubscriptionExpiredEmail(user.getEmail(),
                    user.getFirstName() != null ? user.getFirstName() : user.getEmail());
            } catch (Exception ex) {
                log.warn("Failed to send expiry email to user {}: {}", sub.getUserId(), ex.getMessage());
            }
        });
    }
}
