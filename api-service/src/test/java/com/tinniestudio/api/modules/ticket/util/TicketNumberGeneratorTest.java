package com.tinniestudio.api.modules.ticket.util;

import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketNumberGeneratorTest {

    @Mock private SupportTicketRepository ticketRepository;

    @Test
    void generate_returnsATktPrefixedSixCharacterCode() {
        when(ticketRepository.existsByTicketNumber(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        TicketNumberGenerator generator = new TicketNumberGenerator(ticketRepository);

        String number = generator.generate();

        assertThat(number).startsWith("TKT-");
        assertThat(number).hasSize("TKT-".length() + 6);
    }

    @Test
    void generate_retriesOnCollisionUntilAUniqueCodeIsFound() {
        when(ticketRepository.existsByTicketNumber(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true, true, false);
        TicketNumberGenerator generator = new TicketNumberGenerator(ticketRepository);

        String number = generator.generate();

        assertThat(number).startsWith("TKT-");
    }

    @Test
    void generate_givesUpAfterTenAttemptsAndThrows() {
        when(ticketRepository.existsByTicketNumber(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        TicketNumberGenerator generator = new TicketNumberGenerator(ticketRepository);

        assertThatThrownBy(generator::generate).isInstanceOf(IllegalStateException.class);
    }
}
