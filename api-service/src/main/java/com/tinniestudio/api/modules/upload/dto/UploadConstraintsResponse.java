package com.tinniestudio.api.modules.upload.dto;

import java.util.Set;

public record UploadConstraintsResponse(
    Set<String> allowedMimeTypes,
    long maxFileSizeBytes,
    int maxDurationSeconds,
    boolean audioRequired
) {}
