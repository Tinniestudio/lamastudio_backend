package com.tinniestudio.api.modules.ticket.dto;

import com.tinniestudio.api.shared.entity.TicketMessage;

import java.time.Instant;
import java.util.UUID;

public record TicketMessageResponse(
        UUID id,
        String senderType,
        UUID senderId,
        String body,
        String attachmentUrl,
        Instant createdAt
) {
    public static TicketMessageResponse from(TicketMessage m) {
        return new TicketMessageResponse(
                m.getId(), m.getSenderType().name(), m.getSenderId(), m.getBody(),
                m.getAttachmentUrl(), m.getCreatedAt());
    }
}
