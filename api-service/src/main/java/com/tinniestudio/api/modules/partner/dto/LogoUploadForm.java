package com.tinniestudio.api.modules.partner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.multipart.MultipartFile;

/**
 * Swagger-only shadow of {@code POST /partners/profile/logo}'s multipart request body.
 * Never bound directly — the controller still takes {@code @RequestParam("file") MultipartFile}
 * — this class exists purely so springdoc-openapi documents the "file" part with
 * {@code type: string, format: binary}, which makes Swagger UI render an actual file picker
 * instead of a JSON text box for this operation.
 */
@Schema(description = "Multipart form for uploading a partner logo image")
public class LogoUploadForm {
    @Schema(type = "string", format = "binary", description = "Logo image file (jpg/png/webp)")
    private MultipartFile file;

    // A getter is required for springdoc's schema resolver to discover this as a bean property —
    // a bare field with no accessor is silently dropped from the generated OpenAPI schema.
    public MultipartFile getFile() {
        return file;
    }
}
