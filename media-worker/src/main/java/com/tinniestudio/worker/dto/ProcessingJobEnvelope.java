package com.tinniestudio.worker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter @Setter @NoArgsConstructor
public class ProcessingJobEnvelope {
    private String messageId;
    private String type;
    private int attempt;
    private MediaProcessingJobPayload payload;
}
