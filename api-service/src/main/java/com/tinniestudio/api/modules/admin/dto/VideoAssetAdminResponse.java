package com.tinniestudio.api.modules.admin.dto;

import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.VideoAssetType;

import java.time.Instant;
import java.util.UUID;

public record VideoAssetAdminResponse(
    UUID id,
    UUID contentId,
    ProcessingStatus processingStatus,
    VideoAssetType assetType,
    String storageKey,
    String originalFilename,
    Long fileSizeBytes,
    Instant createdAt
) {
    public static VideoAssetAdminResponse from(VideoAsset va) {
        return new VideoAssetAdminResponse(
            va.getId(),
            va.getContent() != null ? va.getContent().getId() : null,
            va.getProcessingStatus(),
            va.getAssetType(),
            va.getStorageKey(),
            va.getOriginalFilename(),
            va.getFileSizeBytes(),
            va.getCreatedAt()
        );
    }
}
