package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import com.tinniestudio.api.modules.ticket.repository.TicketMessageRepository;
import com.tinniestudio.api.modules.ticket.util.TicketNumberGenerator;
import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketSenderType;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import com.tinniestudio.api.shared.storage.StorageService;
import com.tinniestudio.api.modules.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketServiceImplTest {

    @Mock private SupportTicketRepository ticketRepository;
    @Mock private TicketMessageRepository messageRepository;
    @Mock private TicketNumberGenerator ticketNumberGenerator;
    @Mock private StorageService storageService;
    @Mock private EmailService emailService;
    @Mock private UserRepository userRepository;

    private TicketServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TicketServiceImpl(
                ticketRepository, messageRepository, ticketNumberGenerator,
                storageService, emailService, userRepository);
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
    @DisplayName("createTicket saves an OPEN ticket + the first USER message, and sends the alert email")
    void createTicket_savesTicketAndMessage_andSendsAlert() {
        UUID userId = UUID.randomUUID();
        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("Can't play episode 3");
        request.setBody("It just spins forever.");

        User user = new User();
        user.setId(userId);
        user.setEmail("jane@example.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ticketNumberGenerator.generate()).thenReturn("TKT-ABC123");
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            return t;
        });

        TicketResponse response = service.createTicket(userId, request);

        assertThat(response.ticketNumber()).isEqualTo("TKT-ABC123");
        assertThat(response.status()).isEqualTo("OPEN");

        ArgumentCaptor<TicketMessage> messageCaptor = ArgumentCaptor.forClass(TicketMessage.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSenderType()).isEqualTo(TicketSenderType.USER);
        assertThat(messageCaptor.getValue().getSenderId()).isEqualTo(userId);
        assertThat(messageCaptor.getValue().getBody()).isEqualTo("It just spins forever.");

        verify(emailService).sendNewTicketAlert("TKT-ABC123", "Can't play episode 3", "jane@example.com");
    }

    @Test
    @DisplayName("createTicket still succeeds even if the alert email throws")
    void createTicket_alertEmailFailure_doesNotBlockCreation() {
        UUID userId = UUID.randomUUID();
        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("Billing question");
        request.setBody("Why was I charged twice?");

        User user = new User();
        user.setId(userId);
        user.setEmail("jane@example.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ticketNumberGenerator.generate()).thenReturn("TKT-XYZ999");
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            return t;
        });
        doThrow(new RuntimeException("email provider down")).when(emailService)
                .sendNewTicketAlert(any(), any(), any());

        TicketResponse response = service.createTicket(userId, request);

        assertThat(response.ticketNumber()).isEqualTo("TKT-XYZ999");
    }

    @Test
    @DisplayName("getMyTicket throws ResourceNotFoundException for a ticket that isn't the caller's own")
    void getMyTicket_wrongOwner_throwsNotFound() {
        UUID userId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findByIdAndUserId(ticketId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyTicket(userId, ticketId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("replyToTicket on a CLOSED ticket is rejected")
    void replyToTicket_closedTicket_rejected() {
        UUID userId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.CLOSED);
        when(ticketRepository.findByIdAndUserId(ticket.getId(), userId)).thenReturn(Optional.of(ticket));

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("Still broken!");

        assertThatThrownBy(() -> service.replyToTicket(userId, ticket.getId(), request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("open a new ticket");

        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("replyToTicket on a RESOLVED ticket reopens it to IN_PROGRESS")
    void replyToTicket_resolvedTicket_reopensToInProgress() {
        UUID userId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.RESOLVED);
        when(ticketRepository.findByIdAndUserId(ticket.getId(), userId)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of());

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("Still broken!");

        TicketDetailResponse response = service.replyToTicket(userId, ticket.getId(), request);

        assertThat(response.status()).isEqualTo("IN_PROGRESS");
        verify(messageRepository).save(any(TicketMessage.class));
    }

    @Test
    @DisplayName("replyToTicket on an OPEN ticket leaves status unchanged")
    void replyToTicket_openTicket_statusUnchanged() {
        UUID userId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.OPEN);
        when(ticketRepository.findByIdAndUserId(ticket.getId(), userId)).thenReturn(Optional.of(ticket));
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of());

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("Any update?");

        TicketDetailResponse response = service.replyToTicket(userId, ticket.getId(), request);

        assertThat(response.status()).isEqualTo("OPEN");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTicket rejects an attachment storage key outside the caller's own tickets/{userId}/ prefix")
    void createTicket_attachmentKeyBelongsToAnotherUser_rejected() {
        UUID userId = UUID.randomUUID();
        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("Billing question");
        request.setBody("Why was I charged twice?");
        request.setAttachmentStorageKey("tickets/" + UUID.randomUUID() + "/receipt.png");

        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> service.createTicket(userId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid attachment");

        verify(ticketRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTicket rejects a path-traversal attempt in the attachment storage key")
    void createTicket_attachmentKeyWithTraversal_rejected() {
        UUID userId = UUID.randomUUID();
        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("Billing question");
        request.setBody("Why was I charged twice?");
        request.setAttachmentStorageKey("tickets/" + userId + "/../../avatars/someone-else/photo.jpg");

        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> service.createTicket(userId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid attachment");

        verify(ticketRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTicket accepts an attachment storage key under the caller's own tickets/{userId}/ prefix")
    void createTicket_attachmentKeyOwnedByCaller_accepted() {
        UUID userId = UUID.randomUUID();
        String ownKey = "tickets/" + userId + "/receipt.png";
        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("Billing question");
        request.setBody("Why was I charged twice?");
        request.setAttachmentStorageKey(ownKey);

        User user = new User();
        user.setId(userId);
        user.setEmail("jane@example.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(storageService.objectExists(ownKey)).thenReturn(true);
        when(ticketNumberGenerator.generate()).thenReturn("TKT-ABC123");
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            return t;
        });

        service.createTicket(userId, request);

        ArgumentCaptor<TicketMessage> messageCaptor = ArgumentCaptor.forClass(TicketMessage.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getAttachmentUrl()).isEqualTo(ownKey);
    }
}
