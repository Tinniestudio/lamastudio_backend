package com.tinniestudio.api.modules.ticket.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.service.TicketService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TicketController.class)
@AutoConfigureMockMvc(addFilters = false)
class TicketControllerTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CONTEXT_PATH = "/api/v1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private TicketService ticketService;
    @MockBean private UserRepository userRepository;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    private TicketResponse sampleResponse() {
        return new TicketResponse(
                UUID.fromString(USER_ID), "TKT-ABC123", "Can't play episode 3", "OPEN",
                UUID.fromString(USER_ID), null, Instant.now(), Instant.now());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void create_returns201WithTicketResponse() throws Exception {
        when(ticketService.createTicket(eq(UUID.fromString(USER_ID)), any())).thenReturn(sampleResponse());

        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("Can't play episode 3");
        request.setBody("It just spins forever.");

        mockMvc.perform(post(CONTEXT_PATH + "/tickets")
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ticketNumber").value("TKT-ABC123"));
    }

    @Test
    @WithMockUser(username = USER_ID)
    void create_blankSubject_returns400() throws Exception {
        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("");
        request.setBody("It just spins forever.");

        mockMvc.perform(post(CONTEXT_PATH + "/tickets")
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void list_returns200WithPage() throws Exception {
        when(ticketService.listMyTickets(eq(UUID.fromString(USER_ID)), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(sampleResponse())));

        mockMvc.perform(get(CONTEXT_PATH + "/tickets").contextPath(CONTEXT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
