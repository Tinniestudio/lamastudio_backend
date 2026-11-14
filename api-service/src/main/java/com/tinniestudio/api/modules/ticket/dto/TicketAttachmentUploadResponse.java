package com.tinniestudio.api.modules.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class TicketAttachmentUploadResponse {
    private String uploadUrl;
    private String storageKey;
    private Instant expiresAt;
}
