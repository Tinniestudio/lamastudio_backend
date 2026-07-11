package com.tinniestudio.api.modules.upload.dto;

import java.time.Instant;
import java.util.UUID;

public record UploadSessionResponse(UUID sessionId, String uploadUrl, String storageKey, Instant expiresAt) {}
