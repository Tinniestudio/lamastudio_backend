package com.tinniestudio.worker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter @Setter @NoArgsConstructor
public class MediaProcessingJobPayload {
    private String jobId;
    private UUID videoAssetId;
    private String storageKey;
    private UUID uploadSessionId;
}
