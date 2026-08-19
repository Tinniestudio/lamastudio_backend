package com.tinniestudio.api.modules.upload.dto;

import com.tinniestudio.api.shared.entity.VideoAsset;
import java.time.Instant;
import java.util.UUID;

public record PartnerVideoAssetResponse(
    UUID id,
    String assetType,
    String processingStatus,
    boolean isActive,
    Instant createdAt
) {
    public static PartnerVideoAssetResponse from(VideoAsset a) {
        return new PartnerVideoAssetResponse(
            a.getId(),
            a.getAssetType().name(),
            a.getProcessingStatus() != null ? a.getProcessingStatus().name() : null,
            a.isActive(),
            a.getCreatedAt()
        );
    }
}
