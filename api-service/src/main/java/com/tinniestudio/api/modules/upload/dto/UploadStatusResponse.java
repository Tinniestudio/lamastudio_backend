package com.tinniestudio.api.modules.upload.dto;

import java.util.UUID;

public record UploadStatusResponse(UUID sessionId, String uploadStatus, String processingStatus) {}
