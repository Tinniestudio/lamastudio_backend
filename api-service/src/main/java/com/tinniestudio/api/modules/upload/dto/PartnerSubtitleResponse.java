package com.tinniestudio.api.modules.upload.dto;

import com.tinniestudio.api.shared.entity.Subtitle;
import java.time.Instant;
import java.util.UUID;

public record PartnerSubtitleResponse(
    UUID id,
    UUID videoAssetId,
    String languageCode,
    String label,
    String url,
    String format,
    boolean isDefault,
    Instant createdAt
) {
    public static PartnerSubtitleResponse from(Subtitle s) {
        return new PartnerSubtitleResponse(
            s.getId(),
            s.getVideoAsset().getId(),
            s.getLanguageCode(),
            s.getLabel(),
            s.getFileUrl(),
            s.getFormat() != null ? s.getFormat().name() : null,
            Boolean.TRUE.equals(s.getIsDefault()),
            s.getCreatedAt()
        );
    }
}
