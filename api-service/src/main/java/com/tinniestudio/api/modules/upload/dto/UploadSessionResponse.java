package com.tinniestudio.api.modules.upload.dto;

import java.time.Instant;
import java.util.UUID;

// uploadUrl is null for a multipart session (RAW_VIDEO/TRAILER) — there's no single presigned
// PUT; parts are fetched individually via GET /uploads/sessions/{id}/parts/{partNumber}/url.
// uploadId/partSizeBytes/totalParts are null for every other (single-PUT) upload type.
public record UploadSessionResponse(
    UUID sessionId,
    String uploadUrl,
    String storageKey,
    Instant expiresAt,
    String uploadId,
    Long partSizeBytes,
    Integer totalParts,
    String originalFilename,
    Long expectedMaxSizeBytes
) {}
