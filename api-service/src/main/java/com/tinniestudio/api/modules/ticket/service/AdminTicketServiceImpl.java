package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.dto.UpdateTicketStatusRequest;
import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import com.tinniestudio.api.modules.ticket.repository.TicketMessageRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketSenderType;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import com.tinniestudio.api.shared.queue.RabbitConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTicketServiceImpl implements AdminTicketService {

    private static final Set<TicketStatus> MANUAL_STATUS_TARGETS = Set.of(TicketStatus.RESOLVED, TicketStatus.CLOSED);

    private final SupportTicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TicketAttachmentResolver attachmentResolver;

    @Override
    @Transactional(readOnly = true)
    public Page<TicketResponse> listTickets(TicketStatus status, Pageable pageable) {
        Page<SupportTicket> tickets = status != null
                ? ticketRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : ticketRepository.findAllByOrderByCreatedAtDesc(pageable);
        // Batched into one query instead of submitterEmail()'s per-row findById — this path
        // renders a whole page of tickets at once, so a per-row lookup would mean N extra
        // queries per page load; the single-ticket paths below (getTicket/replyToTicket/
        // updateStatus) don't have that fan-out, so they keep using submitterEmail(UUID).
        Map<UUID, String> emailsByUserId = submitterEmails(tickets.getContent());
        return tickets.map(t -> TicketResponse.from(t, emailsByUserId.get(t.getUserId())));
    }

    @Override
    @Transactional(readOnly = true)
    public TicketDetailResponse getTicket(UUID ticketId) {
        SupportTicket ticket = findTicket(ticketId);
        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        return TicketDetailResponse.from(ticket, messages, submitterEmail(ticket.getUserId()));
    }

    @Override
    @Transactional
    public TicketDetailResponse replyToTicket(UUID adminId, UUID ticketId, ReplyTicketRequest request) {
        SupportTicket ticket = findTicket(ticketId);

        // Closed tickets reject new replies from either side — admins re-open explicitly via
        // updateStatus() before continuing a conversation, same rule as the user-facing endpoint
        // for consistency (the spec only specifies this for user replies, but there's no
        // "half-closed" state — a closed ticket should behave uniformly for both senders).
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new BadRequestException("This ticket is closed.");
        }
        if (ticket.getStatus() == TicketStatus.OPEN) {
            ticket.setStatus(TicketStatus.IN_PROGRESS);
            ticketRepository.save(ticket);
        }

        TicketMessage message = new TicketMessage();
        message.setTicketId(ticketId);
        message.setSenderType(TicketSenderType.ADMIN);
        message.setSenderId(adminId);
        message.setBody(request.getBody());
        message.setAttachmentUrl(attachmentResolver.resolveAttachmentUrl(ticket.getUserId(), request.getAttachmentStorageKey()));
        messageRepository.save(message);

        publishTicketRepliedNotification(ticket);

        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        return TicketDetailResponse.from(ticket, messages, submitterEmail(ticket.getUserId()));
    }

    @Override
    @Transactional
    public TicketResponse updateStatus(UUID ticketId, UpdateTicketStatusRequest request) {
        SupportTicket ticket = findTicket(ticketId);

        if (!MANUAL_STATUS_TARGETS.contains(request.getStatus())) {
            throw new BadRequestException(
                    "Status can only be manually set to RESOLVED or CLOSED — OPEN/IN_PROGRESS are system-managed.");
        }

        ticket.setStatus(request.getStatus());
        ticketRepository.save(ticket);
        return TicketResponse.from(ticket, submitterEmail(ticket.getUserId()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SupportTicket findTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
    }

    private String submitterEmail(UUID userId) {
        return userRepository.findById(userId).map(User::getEmail).orElse(null);
    }

    private Map<UUID, String> submitterEmails(List<SupportTicket> tickets) {
        List<UUID> userIds = tickets.stream().map(SupportTicket::getUserId).distinct().toList();
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));
    }

    // Best-effort, same non-critical-failure handling as every other broadcast/publish helper in
    // this codebase — a failed notification publish must never block the admin's reply itself.
    private void publishTicketRepliedNotification(SupportTicket ticket) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_NOTIFICATIONS, Map.of(
                    "type", "TICKET_REPLIED",
                    "userId", ticket.getUserId().toString(),
                    "ticketId", ticket.getId().toString(),
                    "subject", ticket.getSubject()
            ));
        } catch (Exception e) {
            log.warn("TICKET_REPLIED notification publish failed (non-critical): {}", e.getMessage());
        }
    }
}
