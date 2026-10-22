package com.tinniestudio.api.modules.upload.controller;

import com.tinniestudio.api.modules.upload.config.UploadConfig;
import com.tinniestudio.api.modules.upload.dto.UploadConstraintsResponse;
import com.tinniestudio.api.modules.upload.service.UploadService;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @Mock UploadService uploadService;
    private final UploadConfig uploadConfig = new UploadConfig();
    private UploadController controller;

    @BeforeEach
    void setUp() {
        controller = new UploadController(uploadService, uploadConfig);
    }

    @Test
    void returnsRawVideoConstraintsWithAudioRequired() {
        ResponseEntity<UploadConstraintsResponse> resp = controller.getConstraints(UploadType.RAW_VIDEO);

        UploadConstraintsResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.allowedMimeTypes()).contains("video/mp4", "video/quicktime", "video/x-matroska");
        assertThat(body.maxFileSizeBytes()).isEqualTo(10L * 1024 * 1024 * 1024);
        assertThat(body.maxDurationSeconds()).isEqualTo(UploadConfig.MAX_DURATION_SECONDS);
        assertThat(body.audioRequired()).isTrue();
    }

    @Test
    void returnsTrailerConstraintsWithAudioNotRequired() {
        ResponseEntity<UploadConstraintsResponse> resp = controller.getConstraints(UploadType.TRAILER);

        UploadConstraintsResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.allowedMimeTypes()).containsExactlyInAnyOrder("video/mp4", "video/quicktime");
        assertThat(body.maxFileSizeBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(body.maxDurationSeconds()).isEqualTo(UploadConfig.MAX_DURATION_SECONDS);
        assertThat(body.audioRequired()).isFalse();
    }
}
