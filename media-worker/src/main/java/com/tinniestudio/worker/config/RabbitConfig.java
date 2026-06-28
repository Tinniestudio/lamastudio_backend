package com.tinniestudio.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "tinniestudio.direct";
    public static final String QUEUE_VIDEO_PROCESS = "media.video.process";
    public static final String QUEUE_VIDEO_RETRY   = "media.video.retry";
    public static final String QUEUE_VIDEO_FAILED  = "media.video.failed";

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

    @Bean
    public Queue videoFailedQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_FAILED).build();
    }

    @Bean
    public Binding videoProcessBinding(Queue videoProcessQueue, DirectExchange exchange) {
        return BindingBuilder.bind(videoProcessQueue).to(exchange).with(QUEUE_VIDEO_PROCESS);
    }

    @Bean
    public Binding videoFailedBinding(Queue videoFailedQueue, DirectExchange exchange) {
        return BindingBuilder.bind(videoFailedQueue).to(exchange).with(QUEUE_VIDEO_FAILED);
    }
}
