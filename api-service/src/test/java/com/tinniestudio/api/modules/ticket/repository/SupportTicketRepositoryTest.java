package com.tinniestudio.api.modules.ticket.repository;

import com.tinniestudio.api.shared.entity.DomainEnums.TicketSenderType;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the V62 migration + SupportTicket/TicketMessage entities + repositories work
 * end-to-end against a real Postgres instance (not H2/mocks), matching this module's
 * established @DataJpaTest + Testcontainers convention (see e.g. PartnerApplicationRepositoryTest,
 * CouponRepositoryTest).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class SupportTicketRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_support_ticket_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private SupportTicketRepository ticketRepository;
    @Autowired private TicketMessageRepository messageRepository;

    @Test
    void savesAndReadsBackTicketWithMessage_defaultingStatusToOpen() {
        UUID userId = UUID.randomUUID();

        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId);
        ticket.setSubject("Can't play episode 3");
        ticket.setTicketNumber("TKT-ABC123");
        ticket = ticketRepository.saveAndFlush(ticket);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getId()).isNotNull();
        assertThat(ticket.getCreatedAt()).isNotNull();

        TicketMessage message = new TicketMessage();
        message.setTicketId(ticket.getId());
        message.setSenderType(TicketSenderType.USER);
        message.setSenderId(userId);
        message.setBody("It just spins forever on episode 3.");
        messageRepository.save(message);

        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getBody()).isEqualTo("It just spins forever on episode 3.");

        Optional<SupportTicket> ownTicket = ticketRepository.findByIdAndUserId(ticket.getId(), userId);
        assertThat(ownTicket).isPresent();

        Optional<SupportTicket> wrongOwner = ticketRepository.findByIdAndUserId(ticket.getId(), UUID.randomUUID());
        assertThat(wrongOwner).isEmpty();
    }

    @Test
    void existsByTicketNumber_trueOnlyAfterSave() {
        assertThat(ticketRepository.existsByTicketNumber("TKT-ZZZZZZ")).isFalse();

        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(UUID.randomUUID());
        ticket.setSubject("Billing question");
        ticket.setTicketNumber("TKT-ZZZZZZ");
        ticketRepository.save(ticket);

        assertThat(ticketRepository.existsByTicketNumber("TKT-ZZZZZZ")).isTrue();
    }

    @Test
    void findByStatus_onlyReturnsMatchingStatus() {
        SupportTicket open = new SupportTicket();
        open.setUserId(UUID.randomUUID());
        open.setSubject("Open one");
        open.setTicketNumber("TKT-OPEN01");
        ticketRepository.save(open);

        SupportTicket resolved = new SupportTicket();
        resolved.setUserId(UUID.randomUUID());
        resolved.setSubject("Resolved one");
        resolved.setTicketNumber("TKT-RSLV01");
        resolved.setStatus(TicketStatus.RESOLVED);
        ticketRepository.save(resolved);

        List<SupportTicket> openTickets = ticketRepository
            .findByStatusOrderByCreatedAtDesc(TicketStatus.OPEN, PageRequest.of(0, 10))
            .getContent();

        assertThat(openTickets).extracting(SupportTicket::getTicketNumber).containsExactly("TKT-OPEN01");
    }
}
