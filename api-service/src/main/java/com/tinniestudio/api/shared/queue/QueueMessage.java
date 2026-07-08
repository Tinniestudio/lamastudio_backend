package com.tinniestudio.api.shared.queue;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
public class QueueMessage<T> {

    private String messageId;
    private String type;
    private Instant publishedAt;
    @Setter
    private int attempt;
    private int version;
    private T payload;

    public static <T> QueueMessage<T> of(String type, T payload) {
        QueueMessage<T> msg = new QueueMessage<>();
        msg.messageId = UUID.randomUUID().toString();
        msg.type = type;
        msg.publishedAt = Instant.now();
        msg.attempt = 1;
        msg.version = 1;
        msg.payload = payload;
        return msg;
    }
}
