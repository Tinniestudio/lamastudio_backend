package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.dto.UpdateTicketStatusRequest;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface AdminTicketService {

    Page<TicketResponse> listTickets(TicketStatus status, Pageable pageable);

    TicketDetailResponse getTicket(UUID ticketId);

    TicketDetailResponse replyToTicket(UUID adminId, UUID ticketId, ReplyTicketRequest request);

    TicketResponse updateStatus(UUID ticketId, UpdateTicketStatusRequest request);
}
