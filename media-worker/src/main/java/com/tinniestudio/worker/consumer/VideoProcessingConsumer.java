package com.tinniestudio.worker.consumer;

import com.tinniestudio.worker.config.RabbitConfig;
import com.tinniestudio.worker.dto.ProcessingJobEnvelope;
import com.tinniestudio.worker.processor.VideoProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessingConsumer {

    private final VideoProcessingService processingService;
    private final RetryPublisher retryPublisher;

    @RabbitListener(queues = RabbitConfig.QUEUE_VIDEO_PROCESS, concurrency = "1-2")
    public void consume(ProcessingJobEnvelope envelope) {
        if (envelope.getPayload() == null) {
            log.error("Received malformed message with null payload, discarding");
            return;
        }
        String jobId = envelope.getPayload().getJobId();
        int attempt = Math.max(envelope.getAttempt(), 1);
        log.info("Consuming job={} attempt={}", jobId, attempt);

        try {
            processingService.process(envelope.getPayload());
        } catch (VideoProcessingService.RetryableProcessingException e) {
            log.warn("Retryable failure for job={} attempt={}: {}", jobId, attempt, e.getMessage());
            try {
                retryPublisher.retryOrFail(envelope, attempt);
            } catch (RetryPublisher.MaxAttemptsExceededException ex) {
                log.error("Job={} exhausted retries, routing to failed queue", jobId);
                throw ex;
            }
        }
    }
}
