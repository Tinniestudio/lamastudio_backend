package com.tinniestudio.api.modules.analytics.consumer;

import com.tinniestudio.api.modules.analytics.service.AnalyticsEventProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsConsumerTest {

    @Mock AnalyticsEventProcessor eventProcessor;
    @InjectMocks AnalyticsConsumer consumer;

    @Test
    void handleViewEvent_delegatesToEventProcessor() {
        UUID contentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        consumer.handleAnalyticsEvent(Map.of(
            "type", "VIEW_EVENT",
            "contentId", contentId.toString(),
            "userId", userId.toString()
        ));

        verify(eventProcessor).processViewEvent(contentId);
    }

    @Test
    void handleViewEvent_withNullUserId_stillDelegates() {
        UUID contentId = UUID.randomUUID();
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "VIEW_EVENT");
        msg.put("contentId", contentId.toString());
        msg.put("userId", null);

        consumer.handleAnalyticsEvent(msg);

        verify(eventProcessor).processViewEvent(contentId);
    }

    @Test
    void handleProgressTracked_delegatesCompletionWhen90PercentReached() {
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

        verify(eventProcessor).processCompletionEvent(contentId);
    }

    @Test
    void handleProgressTracked_delegatesCompletionWhenProgressIsNumeric() {
        UUID contentId = UUID.randomUUID();

        // Integer values (as sent from PlaybackServiceImpl via RabbitMQ)
        consumer.handleAnalyticsEvent(Map.of(
            "type", "PROGRESS_TRACKED",
            "contentId", contentId.toString(),
            "userId", UUID.randomUUID().toString(),
            "progressSeconds", 95,
            "durationSeconds", 100
        ));

        verify(eventProcessor).processCompletionEvent(contentId);
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

        verifyNoInteractions(eventProcessor);
    }

    @Test
    void unknownEventType_isIgnored() {
        consumer.handleAnalyticsEvent(Map.of("type", "UNKNOWN"));
        verifyNoInteractions(eventProcessor);
    }
}
