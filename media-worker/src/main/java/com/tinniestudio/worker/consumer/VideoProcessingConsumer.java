package com.tinniestudio.worker.consumer;

import com.tinniestudio.worker.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessingConsumer {

    @RabbitListener(queues = RabbitConfig.QUEUE_VIDEO_PROCESS)
    public void consume(String message) {
        // TODO (Batch 7): deserialize MediaProcessingJob and invoke VideoProcessingService
        log.info("Received video processing job: {}", message);
    }
}
