package com.tinniestudio.api.shared.queue;

import java.time.Duration;

public interface QueuePublisher {
    void publish(String queue, String type, Object payload);
    void publishWithDelay(String queue, String type, Object payload, Duration delay);
}
