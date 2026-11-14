package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import com.tinniestudio.api.modules.ticket.repository.TicketMessageRepository;
import com.tinniestudio.api.modules.ticket.util.TicketNumberGenerator;
import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketSenderType;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import com.tinniestudio.api.shared.storage.StorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final SupportTicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final StorageService storageService;
    private final EmailService emailService;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public TicketResponse createTicket(UUID userId, CreateTicketRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String attachmentUrl = resolveAttachmentUrl(request.getAttachmentStorageKey());

        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId);
        ticket.setSubject(request.getSubject());
        ticket.setTicketNumber(ticketNumberGenerator.generate());
        ticket = ticketRepository.save(ticket);

        TicketMessage message = new TicketMessage();
        message.setTicketId(ticket.getId());
        message.setSenderType(TicketSenderType.USER);
        message.setSenderId(userId);
        message.setBody(request.getBody());
        message.setAttachmentUrl(attachmentUrl);
        messageRepository.save(message);

        // Best-effort — a mail-provider failure must never block ticket submission.
        try {
            emailService.sendNewTicketAlert(ticket.getTicketNumber(), ticket.getSubject(), user.getEmail());
        } catch (Exception ex) {
            log.warn("New-ticket alert email failed for {} (non-critical): {}",
                    ticket.getTicketNumber(), ex.getMessage());
        }

        return TicketResponse.from(ticket);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TicketResponse> listMyTickets(UUID userId, Pageable pageable) {
        return ticketRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(TicketResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketDetailResponse getMyTicket(UUID userId, UUID ticketId) {
        SupportTicket ticket = findOwnTicket(userId, ticketId);
        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        return TicketDetailResponse.from(ticket, messages);
    }

    @Override
    @Transactional
    public TicketDetailResponse replyToTicket(UUID userId, UUID ticketId, ReplyTicketRequest request) {
        SupportTicket ticket = findOwnTicket(userId, ticketId);

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new BadRequestException(
                    "This ticket is closed. Please open a new ticket.");
        }
        if (ticket.getStatus() == TicketStatus.RESOLVED) {
            ticket.setStatus(TicketStatus.IN_PROGRESS);
            ticketRepository.save(ticket);
        }

        String attachmentUrl = resolveAttachmentUrl(request.getAttachmentStorageKey());

        TicketMessage message = new TicketMessage();
        message.setTicketId(ticketId);
        message.setSenderType(TicketSenderType.USER);
        message.setSenderId(userId);
        message.setBody(request.getBody());
        message.setAttachmentUrl(attachmentUrl);
        messageRepository.save(message);

        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        return TicketDetailResponse.from(ticket, messages);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SupportTicket findOwnTicket(UUID userId, UUID ticketId) {
        return ticketRepository.findByIdAndUserId(ticketId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
    }

    private String resolveAttachmentUrl(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        if (!storageService.objectExists(storageKey)) {
            throw new BadRequestException("Upload not found in storage. Please upload the file before submitting.");
        }
        return storageKey;
    }
}
