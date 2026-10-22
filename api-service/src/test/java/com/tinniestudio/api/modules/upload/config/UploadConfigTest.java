package com.tinniestudio.api.modules.upload.config;

import com.tinniestudio.api.shared.entity.DomainEnums.UploadType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UploadConfig")
class UploadConfigTest {

    private final UploadConfig uploadConfig = new UploadConfig();

    // Real-world browsers/OSes frequently report the bare "video/matroska" (no x- prefix) for
    // .mkv files, even though "video/x-matroska" is the RFC-correct MIME type. Both strings refer
    // to the exact same Matroska container format, so both must be accepted for RAW_VIDEO.
    @Test
    @DisplayName("accepts video/matroska (bare, non-x-prefixed) for RAW_VIDEO, same as video/x-matroska")
    void acceptsBareMatroskaMimeTypeForRawVideo() {
        assertThat(uploadConfig.isMimeTypeAllowed(UploadType.RAW_VIDEO, "video/matroska")).isTrue();
        assertThat(uploadConfig.isMimeTypeAllowed(UploadType.RAW_VIDEO, "video/x-matroska")).isTrue();
    }

    @Test
    @DisplayName("getAllowedMimeTypes(RAW_VIDEO) includes both Matroska MIME variants")
    void getAllowedMimeTypesIncludesBothMatroskaVariants() {
        assertThat(uploadConfig.getAllowedMimeTypes(UploadType.RAW_VIDEO))
            .contains("video/x-matroska", "video/matroska");
    }

    @Test
    @DisplayName("rejects an unrelated MIME type for RAW_VIDEO")
    void rejectsUnsupportedMimeTypeForRawVideo() {
        assertThat(uploadConfig.isMimeTypeAllowed(UploadType.RAW_VIDEO, "application/octet-stream")).isFalse();
    }
}
