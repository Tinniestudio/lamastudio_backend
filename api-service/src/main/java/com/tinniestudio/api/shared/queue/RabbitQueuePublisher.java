package com.tinniestudio.api.shared.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitQueuePublisher implements QueuePublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(String queue, String type, Object payload) {
        QueueMessage<Object> message = QueueMessage.of(type, payload);
        log.debug("Publishing message to queue={} type={} messageId={}", queue, type, message.getMessageId());
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, queue, message);
    }

    @Override
    public void publishWithDelay(String queue, String type, Object payload, Duration delay) {
        Objects.requireNonNull(delay, "delay must not be null");
        QueueMessage<Object> message = QueueMessage.of(type, payload);
        log.debug("Publishing delayed message to queue={} type={} delayMs={} messageId={}", queue, type, delay.toMillis(), message.getMessageId());
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, queue, message, msg -> {
            msg.getMessageProperties().setExpiration(String.valueOf(delay.toMillis()));
            return msg;
        });
    }
}
