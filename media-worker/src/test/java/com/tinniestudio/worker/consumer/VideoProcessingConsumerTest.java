package com.tinniestudio.worker.consumer;

import com.tinniestudio.worker.dto.MediaProcessingJobPayload;
import com.tinniestudio.worker.dto.ProcessingJobEnvelope;
import com.tinniestudio.worker.processor.VideoProcessingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoProcessingConsumerTest {

    @Mock VideoProcessingService processingService;
    @Mock RetryPublisher retryPublisher;

    private VideoProcessingConsumer consumer;

    private ProcessingJobEnvelope envelopeFor(UUID assetId, String jobId, int attempt) {
        MediaProcessingJobPayload payload = new MediaProcessingJobPayload();
        payload.setJobId(jobId);
        payload.setVideoAssetId(assetId);
        payload.setStorageKey("uploads/" + assetId + "/raw.mp4");
        ProcessingJobEnvelope envelope = new ProcessingJobEnvelope();
        envelope.setPayload(payload);
        envelope.setAttempt(attempt);
        return envelope;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        consumer = new VideoProcessingConsumer(processingService, retryPublisher);
    }

    @Test
    void schedulesRetryAndDoesNotMarkTerminallyFailedWhenAttemptsRemain() throws Exception {
        UUID assetId = UUID.randomUUID();
        ProcessingJobEnvelope envelope = envelopeFor(assetId, "job-" + assetId, 1);

        doThrow(new VideoProcessingService.RetryableProcessingException("boom", new RuntimeException("boom")))
            .when(processingService).process(envelope.getPayload());

        consumer.consume(envelope);

        verify(retryPublisher).retryOrFail(envelope, 1);
        verify(processingService, never()).markTerminallyFailed(any(), any(), any());
    }

    @Test
    void marksTerminallyFailedAndRethrowsWhenRetriesExhausted() throws Exception {
        UUID assetId = UUID.randomUUID();
        String jobId = "job-" + assetId;
        ProcessingJobEnvelope envelope = envelopeFor(assetId, jobId, RetryPublisher.MAX_ATTEMPTS);

        doThrow(new VideoProcessingService.RetryableProcessingException("boom", new RuntimeException("boom")))
            .when(processingService).process(envelope.getPayload());
        doThrow(new RetryPublisher.MaxAttemptsExceededException("exhausted"))
            .when(retryPublisher).retryOrFail(envelope, RetryPublisher.MAX_ATTEMPTS);

        assertThatThrownBy(() -> consumer.consume(envelope))
            .isInstanceOf(RetryPublisher.MaxAttemptsExceededException.class);

        verify(processingService).markTerminallyFailed(eq(assetId), eq(jobId), eq("boom"));
    }

    @Test
    void stillRethrowsMaxAttemptsExceededEvenIfMarkTerminallyFailedItselfThrows() throws Exception {
        UUID assetId = UUID.randomUUID();
        String jobId = "job-" + assetId;
        ProcessingJobEnvelope envelope = envelopeFor(assetId, jobId, RetryPublisher.MAX_ATTEMPTS);

        doThrow(new VideoProcessingService.RetryableProcessingException("boom", new RuntimeException("boom")))
            .when(processingService).process(envelope.getPayload());
        doThrow(new RetryPublisher.MaxAttemptsExceededException("exhausted"))
            .when(retryPublisher).retryOrFail(envelope, RetryPublisher.MAX_ATTEMPTS);
        doThrow(new RuntimeException("DB hiccup"))
            .when(processingService).markTerminallyFailed(any(), any(), any());

        assertThatThrownBy(() -> consumer.consume(envelope))
            .as("the original MaxAttemptsExceededException must still propagate (preserving DLQ routing) "
                + "even if the terminal-write side effect itself fails")
            .isInstanceOf(RetryPublisher.MaxAttemptsExceededException.class);
    }
}
