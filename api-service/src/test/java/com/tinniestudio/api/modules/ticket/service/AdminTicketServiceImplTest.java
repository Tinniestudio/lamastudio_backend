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
import com.tinniestudio.api.shared.storage.StorageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTicketServiceImplTest {

    @Mock private SupportTicketRepository ticketRepository;
    @Mock private TicketMessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private StorageService storageService;

    private AdminTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminTicketServiceImpl(
                ticketRepository, messageRepository, userRepository, rabbitTemplate, storageService);
    }

    private SupportTicket savedTicket(UUID userId, TicketStatus status) {
        SupportTicket t = new SupportTicket();
        t.setUserId(userId);
        t.setSubject("Can't play episode 3");
        t.setTicketNumber("TKT-ABC123");
        t.setStatus(status);
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        return t;
    }

    @Test
    @DisplayName("getTicket on an unknown id throws ResourceNotFoundException")
    void getTicket_unknownId_throwsNotFound() {
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTicket(ticketId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("replyToTicket on an OPEN ticket transitions it to IN_PROGRESS and publishes TICKET_REPLIED")
    void replyToTicket_openTicket_transitionsAndPublishesNotification() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.OPEN);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of());

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("We're looking into it.");

        TicketDetailResponse response = service.replyToTicket(adminId, ticket.getId(), request);

        assertThat(response.status()).isEqualTo("IN_PROGRESS");

        ArgumentCaptor<TicketMessage> messageCaptor = ArgumentCaptor.forClass(TicketMessage.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSenderType()).isEqualTo(TicketSenderType.ADMIN);
        assertThat(messageCaptor.getValue().getSenderId()).isEqualTo(adminId);

        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.QUEUE_NOTIFICATIONS), eq(Map.of(
                "type", "TICKET_REPLIED",
                "userId", userId.toString(),
                "ticketId", ticket.getId().toString(),
                "subject", "Can't play episode 3"
        )));
    }

    @Test
    @DisplayName("replyToTicket on an IN_PROGRESS ticket leaves status unchanged")
    void replyToTicket_inProgressTicket_statusUnchanged() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.IN_PROGRESS);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of());

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("Still working on it.");

        TicketDetailResponse response = service.replyToTicket(adminId, ticket.getId(), request);

        assertThat(response.status()).isEqualTo("IN_PROGRESS");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    @DisplayName("replyToTicket on a CLOSED ticket is rejected, same rule as the user-facing endpoint")
    void replyToTicket_closedTicket_rejected() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.CLOSED);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("One more note.");

        assertThatThrownBy(() -> service.replyToTicket(adminId, ticket.getId(), request))
                .isInstanceOf(BadRequestException.class);

        verify(messageRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("replyToTicket rejects an attachment key outside the ticket owner's own tickets/{userId}/ prefix")
    void replyToTicket_attachmentKeyNotUnderTicketOwner_rejected() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.OPEN);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("Here's a doc.");
        request.setAttachmentStorageKey("tickets/" + UUID.randomUUID() + "/receipt.png");

        assertThatThrownBy(() -> service.replyToTicket(adminId, ticket.getId(), request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid attachment");

        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("replyToTicket accepts an attachment key under the ticket owner's own prefix")
    void replyToTicket_attachmentKeyUnderTicketOwner_accepted() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.OPEN);
        String ownKey = "tickets/" + userId + "/receipt.png";
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of());
        when(storageService.objectExists(ownKey)).thenReturn(true);

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("Here's a doc.");
        request.setAttachmentStorageKey(ownKey);

        service.replyToTicket(adminId, ticket.getId(), request);

        ArgumentCaptor<TicketMessage> messageCaptor = ArgumentCaptor.forClass(TicketMessage.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getAttachmentUrl()).isEqualTo(ownKey);
    }

    @Test
    @DisplayName("updateStatus accepts RESOLVED")
    void updateStatus_resolved_succeeds() {
        UUID userId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.IN_PROGRESS);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest();
        request.setStatus(TicketStatus.RESOLVED);

        TicketResponse response = service.updateStatus(ticket.getId(), request);

        assertThat(response.status()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("updateStatus rejects OPEN and IN_PROGRESS as manual targets — those are system-managed transitions")
    void updateStatus_openOrInProgress_rejected() {
        UUID userId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.RESOLVED);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest();
        request.setStatus(TicketStatus.OPEN);

        assertThatThrownBy(() -> service.updateStatus(ticket.getId(), request))
                .isInstanceOf(BadRequestException.class);

        verify(ticketRepository, never()).save(any());
    }
}
