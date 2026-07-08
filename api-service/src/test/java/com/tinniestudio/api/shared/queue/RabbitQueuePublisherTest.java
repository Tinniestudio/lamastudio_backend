package com.tinniestudio.api.shared.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RabbitQueuePublisher")
class RabbitQueuePublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private RabbitQueuePublisher publisher;

    @Captor
    private ArgumentCaptor<QueueMessage<?>> messageCaptor;

    @Nested
    @DisplayName("publish()")
    class PublishTests {

        @Test
        @DisplayName("sends to correct exchange with queue name as routing key and sets all envelope fields")
        void sendsToCorrectExchangeAndRoutingKey() {
            publisher.publish("media.video.process", "VIDEO_PROCESSING_REQUESTED", Map.of("videoAssetId", "abc-123"));

            verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq("media.video.process"),
                messageCaptor.capture()
            );

            QueueMessage<?> sent = messageCaptor.getValue();
            assertThat(sent.getMessageId()).isNotNull();
            assertThat(sent.getType()).isEqualTo("VIDEO_PROCESSING_REQUESTED");
            assertThat(sent.getPublishedAt()).isNotNull();
            assertThat(sent.getAttempt()).isEqualTo(1);
            assertThat(sent.getVersion()).isEqualTo(1);
            assertThat(sent.getPayload()).isEqualTo(Map.of("videoAssetId", "abc-123"));
        }

        @Test
        @DisplayName("each call generates a unique messageId")
        void eachCallGeneratesUniqueMessageId() {
            publisher.publish("notifications.send", "WELCOME_EMAIL", Map.of("userId", "u1"));
            publisher.publish("notifications.send", "WELCOME_EMAIL", Map.of("userId", "u2"));

            verify(rabbitTemplate, org.mockito.Mockito.times(2))
                .convertAndSend(eq(RabbitConfig.EXCHANGE), eq("notifications.send"), messageCaptor.capture());

            var ids = messageCaptor.getAllValues().stream().map(QueueMessage::getMessageId).toList();
            assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
        }
    }

    @Nested
    @DisplayName("publishWithDelay()")
    class PublishWithDelayTests {

        @Test
        @DisplayName("wraps payload in QueueMessage with correct type")
        void wrapsPayloadInEnvelope() {
            AtomicReference<QueueMessage<?>> captured = new AtomicReference<>();
            doAnswer(inv -> { captured.set(inv.getArgument(2)); return null; })
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(QueueMessage.class), any(MessagePostProcessor.class));

            publisher.publishWithDelay("media.video.retry", "VIDEO_RETRY", Map.of("jobId", "j1"), Duration.ofMinutes(1));

            assertThat(captured.get().getType()).isEqualTo("VIDEO_RETRY");
            assertThat(captured.get().getMessageId()).isNotNull();
            assertThat(captured.get().getPayload()).isEqualTo(Map.of("jobId", "j1"));
        }

        @Test
        @DisplayName("sets x-expiration header to delay in milliseconds")
        void setsExpirationHeader() {
            AtomicReference<MessagePostProcessor> capturedProcessor = new AtomicReference<>();
            doAnswer(inv -> { capturedProcessor.set(inv.getArgument(3)); return null; })
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(QueueMessage.class), any(MessagePostProcessor.class));

            publisher.publishWithDelay("media.video.retry", "VIDEO_RETRY", Map.of(), Duration.ofMinutes(1));

            MessageProperties props = new MessageProperties();
            capturedProcessor.get().postProcessMessage(new Message(new byte[0], props));
            assertThat(props.getExpiration()).isEqualTo("60000");
        }

        @Test
        @DisplayName("throws NullPointerException when delay is null")
        void throwsWhenDelayIsNull() {
            assertThatNullPointerException()
                .isThrownBy(() -> publisher.publishWithDelay("media.video.retry", "VIDEO_RETRY", Map.of(), null))
                .withMessage("delay must not be null");
        }
    }
}
