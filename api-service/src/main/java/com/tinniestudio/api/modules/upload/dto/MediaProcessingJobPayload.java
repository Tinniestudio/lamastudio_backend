package com.tinniestudio.api.modules.upload.dto;

import java.util.UUID;

public record MediaProcessingJobPayload(
    String jobId,
    UUID videoAssetId,
    String storageKey,
    UUID uploadSessionId
) {}
