package com.tinniestudio.worker.consumer;

import com.tinniestudio.worker.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryPublisher {

    private static final long[] DELAY_MS = {
        60_000L,   // attempt 1 failed → retry after 1 min
        300_000L   // attempt 2 failed → retry after 5 min
    };
    public static final int MAX_ATTEMPTS = 3;

    private final RabbitTemplate rabbitTemplate;

    /**
     * Publishes message to retry queue with TTL delay based on current attempt count.
     * Throws MaxAttemptsExceededException when currentAttempt >= MAX_ATTEMPTS.
     */
    public void retryOrFail(Object envelope, int currentAttempt) {
        if (currentAttempt >= MAX_ATTEMPTS) {
            throw new MaxAttemptsExceededException(
                "Max attempts (" + MAX_ATTEMPTS + ") exceeded, routing to DLQ");
        }
        long delayMs = DELAY_MS[currentAttempt - 1];
        log.info("Scheduling retry attempt {} in {}ms", currentAttempt + 1, delayMs);
        rabbitTemplate.convertAndSend(
            RabbitConfig.EXCHANGE,
            RabbitConfig.QUEUE_VIDEO_RETRY,
            envelope,
            msg -> {
                msg.getMessageProperties().setExpiration(String.valueOf(delayMs));
                return msg;
            }
        );
    }

    public static class MaxAttemptsExceededException extends RuntimeException {
        public MaxAttemptsExceededException(String message) { super(message); }
    }
}
