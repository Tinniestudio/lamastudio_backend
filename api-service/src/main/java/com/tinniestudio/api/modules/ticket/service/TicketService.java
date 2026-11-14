package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TicketService {

    TicketResponse createTicket(UUID userId, CreateTicketRequest request);

    Page<TicketResponse> listMyTickets(UUID userId, Pageable pageable);

    TicketDetailResponse getMyTicket(UUID userId, UUID ticketId);

    TicketDetailResponse replyToTicket(UUID userId, UUID ticketId, ReplyTicketRequest request);
}
