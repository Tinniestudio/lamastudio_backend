package com.tinniestudio.api.shared.storage;

import java.time.Instant;

public record PresignedUploadResult(String uploadUrl, String storageKey, Instant expiresAt) {}
