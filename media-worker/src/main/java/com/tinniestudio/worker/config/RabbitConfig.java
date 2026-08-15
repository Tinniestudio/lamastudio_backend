package com.tinniestudio.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE            = "tinniestudio.direct";
    public static final String QUEUE_VIDEO_PROCESS = "media.video.process";
    public static final String QUEUE_VIDEO_RETRY   = "media.video.retry";
    public static final String QUEUE_VIDEO_FAILED  = "media.video.failed";
    public static final String QUEUE_NOTIFICATIONS = "notifications.send";

    @Bean
    public DirectExchange exchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue videoProcessQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_PROCESS)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_VIDEO_FAILED)
                .build();
    }

    /** TTL retry queue: message expires → dead-lettered back to process queue. */
    @Bean
    public Queue videoRetryQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_RETRY)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_VIDEO_PROCESS)
                .build();
    }

    @Bean
    public Queue videoFailedQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_FAILED).build();
    }

    @Bean
    public Queue notificationsQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFICATIONS).build();
    }

    @Bean
    public Binding videoProcessBinding(Queue videoProcessQueue, DirectExchange exchange) {
        return BindingBuilder.bind(videoProcessQueue).to(exchange).with(QUEUE_VIDEO_PROCESS);
    }

    @Bean
    public Binding videoRetryBinding(Queue videoRetryQueue, DirectExchange exchange) {
        return BindingBuilder.bind(videoRetryQueue).to(exchange).with(QUEUE_VIDEO_RETRY);
    }

    @Bean
    public Binding videoFailedBinding(Queue videoFailedQueue, DirectExchange exchange) {
        return BindingBuilder.bind(videoFailedQueue).to(exchange).with(QUEUE_VIDEO_FAILED);
    }

    @Bean
    public Binding notificationsBinding(Queue notificationsQueue, DirectExchange exchange) {
        return BindingBuilder.bind(notificationsQueue).to(exchange).with(QUEUE_NOTIFICATIONS);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
