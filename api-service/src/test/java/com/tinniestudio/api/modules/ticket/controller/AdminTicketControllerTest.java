package com.tinniestudio.api.modules.ticket.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.dto.UpdateTicketStatusRequest;
import com.tinniestudio.api.modules.ticket.service.AdminTicketService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminTicketController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminTicketControllerTest {

    private static final String CONTEXT_PATH = "/api/v1";
    private static final String ADMIN_ID = "00000000-0000-0000-0000-000000000099";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AdminTicketService adminTicketService;
    @MockBean private UserRepository userRepository;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    private TicketResponse sampleResponse() {
        UUID id = UUID.randomUUID();
        return new TicketResponse(id, "TKT-ABC123", "Can't play episode 3", "OPEN",
                id, "jane@example.com", Instant.now(), Instant.now());
    }

    private TicketDetailResponse sampleDetailResponse() {
        UUID id = UUID.randomUUID();
        return new TicketDetailResponse(id, "TKT-ABC123", "Can't play episode 3", "OPEN",
                id, "jane@example.com", Instant.now(), Instant.now(), List.of());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_noFilter_returns200WithPage() throws Exception {
        when(adminTicketService.listTickets(isNull(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get(CONTEXT_PATH + "/admin/tickets").contextPath(CONTEXT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("controller class is annotated hasRole('ADMIN') — @WebMvcTest doesn't load "
            + "@EnableMethodSecurity, so an end-to-end 403 assertion here would silently pass "
            + "regardless of role; this is a structural check on the annotation itself, same "
            + "pattern as AnalyticsControllerTest's getAdminRevenueAnalytics_isAnnotatedAdminOnly")
    void controller_isAnnotatedAdminOnly() {
        var preAuthorize = AdminTicketController.class
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);

        org.assertj.core.api.Assertions.assertThat(preAuthorize).isNotNull();
        org.assertj.core.api.Assertions.assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getOne_returns200WithTicketDetail() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(adminTicketService.getTicket(eq(ticketId))).thenReturn(sampleDetailResponse());

        mockMvc.perform(get(CONTEXT_PATH + "/admin/tickets/" + ticketId).contextPath(CONTEXT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketNumber").value("TKT-ABC123"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void reply_returns200WithUpdatedThread() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(adminTicketService.replyToTicket(eq(UUID.fromString(ADMIN_ID)), eq(ticketId), any()))
                .thenReturn(sampleDetailResponse());

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("We're looking into it.");

        mockMvc.perform(post(CONTEXT_PATH + "/admin/tickets/" + ticketId + "/messages")
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketNumber").value("TKT-ABC123"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateStatus_returns200WithUpdatedTicket() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(adminTicketService.updateStatus(eq(ticketId), any())).thenReturn(sampleResponse());

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest();
        request.setStatus(TicketStatus.RESOLVED);

        mockMvc.perform(patch(CONTEXT_PATH + "/admin/tickets/" + ticketId + "/status")
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketNumber").value("TKT-ABC123"));
    }
}
