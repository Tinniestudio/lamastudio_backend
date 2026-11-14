package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadResponse;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.storage.PresignedUploadResult;
import com.tinniestudio.api.shared.storage.StorageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketAttachmentResolverTest {

    @Mock private StorageService storageService;
    private AppProperties appProperties;
    private TicketAttachmentResolver resolver;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        AppProperties.Cdn cdn = new AppProperties.Cdn();
        cdn.setBaseUrl("https://cdn.test");
        appProperties.setCdn(cdn);
        resolver = new TicketAttachmentResolver(storageService, appProperties);
    }

    @Test
    @DisplayName("initiateUpload rejects an unsupported mime type")
    void initiateUpload_unsupportedMime_throws() {
        TicketAttachmentUploadRequest request = new TicketAttachmentUploadRequest();
        request.setMimeType("application/zip");
        request.setFileSizeBytes(1024L);

        assertThatThrownBy(() -> resolver.initiateUpload(request, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    @DisplayName("initiateUpload rejects a file over the 10 MB limit")
    void initiateUpload_oversized_throws() {
        TicketAttachmentUploadRequest request = new TicketAttachmentUploadRequest();
        request.setMimeType("image/png");
        request.setFileSizeBytes(11_000_000L);

        assertThatThrownBy(() -> resolver.initiateUpload(request, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10 MB");
    }

    @Test
    @DisplayName("initiateUpload accepts a PDF within the size limit and returns a presigned URL")
    void initiateUpload_validPdf_returnsPresignedUrl() {
        TicketAttachmentUploadRequest request = new TicketAttachmentUploadRequest();
        request.setMimeType("application/pdf");
        request.setFileSizeBytes(2048L);
        Instant expiry = Instant.now().plusSeconds(900);
        when(storageService.generateUploadUrl(any(), any(), any(Long.class), any(Duration.class)))
                .thenReturn(new PresignedUploadResult("https://upload.test/x", "tickets/x.pdf", expiry));

        TicketAttachmentUploadResponse response = resolver.initiateUpload(request, UUID.randomUUID());

        assertThat(response.getUploadUrl()).isEqualTo("https://upload.test/x");
        assertThat(response.getStorageKey()).isEqualTo("tickets/x.pdf");
    }

    @Test
    @DisplayName("resolveAttachmentUrl returns null when no storage key was provided")
    void resolveAttachmentUrl_noKey_returnsNull() {
        UUID userId = UUID.randomUUID();
        assertThat(resolver.resolveAttachmentUrl(userId, null)).isNull();
        assertThat(resolver.resolveAttachmentUrl(userId, "")).isNull();
    }

    @Test
    @DisplayName("resolveAttachmentUrl rejects a storage key the upload never actually landed at")
    void resolveAttachmentUrl_missingObject_throws() {
        UUID userId = UUID.randomUUID();
        String key = "tickets/" + userId + "/missing.png";
        when(storageService.objectExists(key)).thenReturn(false);

        assertThatThrownBy(() -> resolver.resolveAttachmentUrl(userId, key))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Please upload the file");
    }

    @Test
    @DisplayName("resolveAttachmentUrl builds the CDN URL for a storage key that really exists under the caller's own prefix")
    void resolveAttachmentUrl_existingObject_buildsCdnUrl() {
        UUID userId = UUID.randomUUID();
        String key = "tickets/" + userId + "/real.png";
        when(storageService.objectExists(key)).thenReturn(true);

        assertThat(resolver.resolveAttachmentUrl(userId, key)).isEqualTo("https://cdn.test/" + key);
    }

    @Test
    @DisplayName("resolveAttachmentUrl rejects a storage key outside the given owner's own tickets/{ownerId}/ prefix")
    void resolveAttachmentUrl_wrongOwnerPrefix_throws() {
        UUID userId = UUID.randomUUID();
        String someoneElsesKey = "tickets/" + UUID.randomUUID() + "/real.png";

        assertThatThrownBy(() -> resolver.resolveAttachmentUrl(userId, someoneElsesKey))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid attachment");
    }

    @Test
    @DisplayName("resolveAttachmentUrl rejects a path-traversal attempt in the storage key")
    void resolveAttachmentUrl_traversal_throws() {
        UUID userId = UUID.randomUUID();
        String traversalKey = "tickets/" + userId + "/../../avatars/someone-else/photo.jpg";

        assertThatThrownBy(() -> resolver.resolveAttachmentUrl(userId, traversalKey))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid attachment");
    }
}
