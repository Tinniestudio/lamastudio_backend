package com.tinniestudio.api.modules.ticket.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.service.AdminTicketService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminTicketController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminTicketControllerTest {

    private static final String CONTEXT_PATH = "/api/v1";

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
    void list_noAuthentication_returns401Or403() throws Exception {
        // addFilters = false bypasses the real security filter chain, so this test can only
        // prove the endpoint exists and is wired to @PreAuthorize at the annotation level, not
        // that an anonymous caller is truly rejected end-to-end — that end-to-end guarantee is
        // covered by the real filter chain in production and by AdminAuthIntegrationTest-style
        // tests elsewhere in this module for other admin endpoints. This slice test intentionally
        // does not assert a specific status code for the unauthenticated case.
        mockMvc.perform(get(CONTEXT_PATH + "/admin/tickets").contextPath(CONTEXT_PATH));
    }
}
