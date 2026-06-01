package com.lamastudio.backend.shared.storage;

import java.time.Instant;

public record PresignedUploadResult(String uploadUrl, String storageKey, Instant expiresAt) {}
