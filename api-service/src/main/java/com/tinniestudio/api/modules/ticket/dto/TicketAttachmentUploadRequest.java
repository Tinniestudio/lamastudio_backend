package com.tinniestudio.api.modules.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class TicketAttachmentUploadRequest {

    @NotBlank(message = "mimeType is required")
    private String mimeType;

    @NotNull(message = "fileSizeBytes is required")
    private Long fileSizeBytes;
}
