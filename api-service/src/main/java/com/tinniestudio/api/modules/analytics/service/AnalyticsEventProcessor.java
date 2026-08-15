package com.tinniestudio.api.modules.analytics.service;

import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Dedicated Spring bean that owns the transactional write-side of analytics event
 * processing (called by {@link com.tinniestudio.api.modules.analytics.consumer.AnalyticsConsumer}).
 *
 * <p>This logic used to live as {@code protected @Transactional} methods directly on the
 * {@code @RabbitListener} class, invoked via plain {@code this.method(...)} self-invocation.
 * Spring's proxy-based {@code @Transactional} can only intercept calls that arrive through the
 * Spring-managed proxy, so a same-class self-invocation bypasses the proxy entirely — the
 * {@code @Transactional} annotation was silently a no-op, and each repository call committed in
 * its own independent transaction. If the second write failed, the first write's effect was
 * never rolled back.
 *
 * <p>Extracting the logic into this separate bean means the caller now invokes a genuinely
 * different Spring-managed bean, so the call goes through the real transactional proxy and
 * {@code @Transactional} actually wraps every write below in one atomic unit.</p>
 */
@Service
@RequiredArgsConstructor
public class AnalyticsEventProcessor {

    private final ContentRepository contentRepo;
    private final ContentAnalyticsDailyRepository dailyRepo;

    /**
     * Atomically increments {@code contents.view_count} and upserts the corresponding
     * {@code content_analytics_daily} row for today. Both writes commit or roll back together.
     */
    @Transactional
    public void processViewEvent(UUID contentId) {
        contentRepo.incrementViewCount(contentId);
        dailyRepo.upsertViewEvent(contentId, LocalDate.now());
    }

    /**
     * Upserts a completion event into {@code content_analytics_daily} for today.
     */
    @Transactional
    public void processCompletionEvent(UUID contentId) {
        dailyRepo.upsertCompletionEvent(contentId, LocalDate.now());
    }
}
