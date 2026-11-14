package com.tinniestudio.api.modules.ticket.dto;

import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketDetailResponse(
        UUID id,
        String ticketNumber,
        String subject,
        String status,
        UUID userId,
        String submitterEmail,
        Instant createdAt,
        Instant updatedAt,
        List<TicketMessageResponse> messages
) {
    public static TicketDetailResponse from(SupportTicket t, List<TicketMessage> messages) {
        return from(t, messages, null);
    }

    public static TicketDetailResponse from(SupportTicket t, List<TicketMessage> messages, String submitterEmail) {
        return new TicketDetailResponse(
                t.getId(), t.getTicketNumber(), t.getSubject(), t.getStatus().name(),
                t.getUserId(), submitterEmail, t.getCreatedAt(), t.getUpdatedAt(),
                messages.stream().map(TicketMessageResponse::from).toList());
    }
}
