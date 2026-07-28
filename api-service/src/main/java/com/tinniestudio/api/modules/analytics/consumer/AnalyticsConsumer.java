package com.tinniestudio.api.modules.analytics.consumer;

import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsConsumer {

    private final ContentRepository contentRepo;
    private final ContentAnalyticsDailyRepository dailyRepo;

    @RabbitListener(queues = RabbitConfig.QUEUE_ANALYTICS_INGEST)
    public void handleAnalyticsEvent(Map<String, Object> message) {
        String type = (String) message.get("type");
        try {
            switch (type != null ? type : "") {
                case "VIEW_EVENT"       -> handleViewEvent(message);
                case "PROGRESS_TRACKED" -> handleProgressTracked(message);
                default                 -> log.debug("Ignoring analytics event type: {}", type);
            }
        } catch (Exception e) {
            log.error("Error processing analytics event {}: {}", type, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    @Transactional
    protected void handleViewEvent(Map<String, Object> message) {
        String contentIdStr = (String) message.get("contentId");
        if (contentIdStr == null || contentIdStr.isBlank()) {
            log.warn("VIEW_EVENT missing contentId — skipping");
            return;
        }
        UUID contentId = UUID.fromString(contentIdStr);
        contentRepo.incrementViewCount(contentId);
        dailyRepo.upsertViewEvent(contentId, LocalDate.now());
        log.debug("VIEW_EVENT processed for content {}", contentId);
    }

    @Transactional
    protected void handleProgressTracked(Map<String, Object> message) {
        String contentIdStr = (String) message.get("contentId");
        if (contentIdStr == null || contentIdStr.isBlank()) {
            log.warn("PROGRESS_TRACKED missing contentId — skipping");
            return;
        }

        Object progressObj  = message.get("progressSeconds");
        Object durationObj  = message.get("durationSeconds");
        if (progressObj == null || durationObj == null) {
            log.warn("PROGRESS_TRACKED missing progress/duration values — skipping");
            return;
        }

        double progress = toDouble(progressObj);
        double duration = toDouble(durationObj);
        if (duration <= 0) return;

        if (progress / duration >= 0.90) {
            UUID contentId = UUID.fromString(contentIdStr);
            dailyRepo.upsertCompletionEvent(contentId, LocalDate.now());
            log.debug("PROGRESS_TRACKED completion upserted for content {}", contentId);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a message field value to a double. Handles both {@link Number}
     * (as received from RabbitMQ serialisation) and {@link String} (used in unit tests).
     */
    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(value.toString());
    }
}
