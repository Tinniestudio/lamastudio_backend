package com.tinniestudio.api.modules.upload.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.UploadType;
import com.tinniestudio.api.shared.entity.DomainEnums.TargetEntityType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CreateUploadSessionRequest(
    @NotNull UploadType uploadType,
    TargetEntityType targetEntityType,
    UUID targetEntityId,
    String originalFilename,
    @NotNull String mimeType,
    @NotNull @Positive Long fileSizeBytes
) {}
