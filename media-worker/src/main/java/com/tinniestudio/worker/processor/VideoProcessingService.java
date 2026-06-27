package com.tinniestudio.worker.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VideoProcessingService {

    // TODO (Batch 7): implement full FFmpeg HLS pipeline
    // Stages: VALIDATING -> DOWNLOADING -> PROBING -> TRANSCODING -> UPLOADING -> FINALIZING -> CLEANUP
    public void process(String videoAssetId) {
        log.info("Processing video asset: {}", videoAssetId);
        throw new UnsupportedOperationException("Media pipeline not yet implemented - coming in Batch 7");
    }
}
