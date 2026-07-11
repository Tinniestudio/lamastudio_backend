package com.tinniestudio.api.modules.upload.dto;

import java.util.UUID;

public record CompleteUploadResponse(UUID mediaFileId, UUID videoAssetId) {}
