package com.lamastudio.backend.modules.billing.service;

import com.lamastudio.backend.modules.billing.repository.UserSubscriptionRepository;
import com.lamastudio.backend.shared.cache.CacheService;
import com.lamastudio.backend.shared.entity.DomainEnums.SubscriptionStatus;
import com.lamastudio.backend.shared.entity.UserSubscription;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityServiceImpl implements CapabilityService {

    private final UserSubscriptionRepository userSubscriptionRepository;
    private final CacheService cacheService;

    private static final String QUOTA_KEY_PREFIX = "tinnie:content_quota:";

    @Override
    @Transactional(readOnly = true)
    public boolean canWatch(UUID userId) {
        Optional<UserSubscription> subOpt =
                userSubscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE);

        if (subOpt.isEmpty()) return false;

        UserSubscription sub = subOpt.get();
        Integer limit = sub.getPlan() != null ? sub.getPlan().getContentLimit() : null;

        if (limit == null) return true;  // unlimited (paid tier)
        if (limit == 0) return false;    // admin-disabled free tier

        // Redis fast path
        String key = QUOTA_KEY_PREFIX + userId;
        Optional<String> cached = cacheService.get(key);
        int used;
        if (cached.isPresent()) {
            used = Integer.parseInt(cached.get());
        } else {
            used = sub.getContentWatchesUsed();
            cacheService.set(key, String.valueOf(used), Duration.ofDays(30));
        }

        return used < limit;
    }

    @Override
    @Transactional
    public void recordWatch(UUID userId) {
        userSubscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .ifPresent(sub -> {
                    sub.setContentWatchesUsed(sub.getContentWatchesUsed() + 1);
                    userSubscriptionRepository.save(sub);
                    // Invalidate cache so next canWatch() reads fresh DB value
                    cacheService.delete(QUOTA_KEY_PREFIX + userId);
                    log.debug("Recorded watch for user {}, total={}", userId, sub.getContentWatchesUsed());
                });
    }
}
