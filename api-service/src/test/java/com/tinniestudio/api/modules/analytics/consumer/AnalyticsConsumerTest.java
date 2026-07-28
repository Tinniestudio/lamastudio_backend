package com.tinniestudio.api.modules.analytics.consumer;

import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsConsumerTest {

    @Mock ContentRepository contentRepo;
    @Mock ContentAnalyticsDailyRepository dailyRepo;
    @InjectMocks AnalyticsConsumer consumer;

    @Test
    void handleViewEvent_incrementsViewCountAndUpsertsDailyRow() {
        UUID contentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        consumer.handleAnalyticsEvent(Map.of(
            "type", "VIEW_EVENT",
            "contentId", contentId.toString(),
            "userId", userId.toString()
        ));

        verify(contentRepo).incrementViewCount(contentId);
        verify(dailyRepo).upsertViewEvent(eq(contentId), any(LocalDate.class));
    }

    @Test
    void handleViewEvent_withNullUserId_stillIncrements() {
        UUID contentId = UUID.randomUUID();
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "VIEW_EVENT");
        msg.put("contentId", contentId.toString());
        msg.put("userId", null);

        consumer.handleAnalyticsEvent(msg);

        verify(contentRepo).incrementViewCount(contentId);
        verify(dailyRepo).upsertViewEvent(eq(contentId), any(LocalDate.class));
    }

    @Test
    void handleProgressTracked_upsertsCompletionWhen90PercentReached() {
        UUID contentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // String values (as sent from test) — consumer handles both String and Number
        consumer.handleAnalyticsEvent(Map.of(
            "type", "PROGRESS_TRACKED",
            "contentId", contentId.toString(),
            "userId", userId.toString(),
            "progressSeconds", "90",
            "durationSeconds", "100"
        ));

        verify(dailyRepo).upsertCompletionEvent(eq(contentId), any(LocalDate.class));
    }

    @Test
    void handleProgressTracked_upsertsCompletionWhenProgressIsNumeric() {
        UUID contentId = UUID.randomUUID();

        // Integer values (as sent from PlaybackServiceImpl via RabbitMQ)
        consumer.handleAnalyticsEvent(Map.of(
            "type", "PROGRESS_TRACKED",
            "contentId", contentId.toString(),
            "userId", UUID.randomUUID().toString(),
            "progressSeconds", 95,
            "durationSeconds", 100
        ));

        verify(dailyRepo).upsertCompletionEvent(eq(contentId), any(LocalDate.class));
    }

    @Test
    void handleProgressTracked_skipsCompletionBelow90Percent() {
        UUID contentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        consumer.handleAnalyticsEvent(Map.of(
            "type", "PROGRESS_TRACKED",
            "contentId", contentId.toString(),
            "userId", userId.toString(),
            "progressSeconds", "50",
            "durationSeconds", "100"
        ));

        verifyNoInteractions(dailyRepo);
    }

    @Test
    void unknownEventType_isIgnored() {
        consumer.handleAnalyticsEvent(Map.of("type", "UNKNOWN"));
        verifyNoInteractions(contentRepo, dailyRepo);
    }
}
