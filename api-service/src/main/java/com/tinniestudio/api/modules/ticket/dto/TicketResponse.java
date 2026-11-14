package com.tinniestudio.api.modules.ticket.dto;

import com.tinniestudio.api.shared.entity.SupportTicket;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
        UUID id,
        String ticketNumber,
        String subject,
        String status,
        UUID userId,
        String submitterEmail,
        Instant createdAt,
        Instant updatedAt
) {
    public static TicketResponse from(SupportTicket t) {
        return from(t, null);
    }

    // The submitterEmail overload has no caller yet — it's for the admin-facing list/detail
    // endpoints (Task 6), which need to show who submitted each ticket; the user-facing path
    // above always passes null since a user already knows their own email.
    public static TicketResponse from(SupportTicket t, String submitterEmail) {
        return new TicketResponse(
                t.getId(), t.getTicketNumber(), t.getSubject(), t.getStatus().name(),
                t.getUserId(), submitterEmail, t.getCreatedAt(), t.getUpdatedAt());
    }
}
