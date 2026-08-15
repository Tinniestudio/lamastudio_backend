package com.tinniestudio.api.modules.partner.dto;

import com.tinniestudio.api.shared.entity.UploadSession;
import java.time.Instant;
import java.util.UUID;

public record PartnerUploadSummaryResponse(
    UUID id,
    String uploadType,
    UUID targetEntityId,
    String storageKey,
    String originalFilename,
    String uploadStatus,
    Long fileSizeBytes,
    Instant createdAt
) {
    public static PartnerUploadSummaryResponse from(UploadSession s) {
        return new PartnerUploadSummaryResponse(
            s.getId(),
            s.getUploadType() != null ? s.getUploadType().name() : null,
            s.getTargetEntityId(),
            s.getStorageKey(),
            s.getOriginalFilename(),
            s.getUploadStatus() != null ? s.getUploadStatus().name() : null,
            s.getFileSizeBytes(),
            s.getCreatedAt()
        );
    }
}
