package com.tinniestudio.api.modules.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.admin.service.PartnerApplicationService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminPartnerApplicationController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminPartnerApplicationControllerTest {

    static final String ADMIN_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean PartnerApplicationService applicationService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    // PartnerApplicationResponse.status is String, not PartnerApplicationStatus enum
    PartnerApplicationResponse sampleResponse() {
        return new PartnerApplicationResponse(
            UUID.randomUUID(), UUID.randomUUID(), "Acme Corp", "We make videos",
            "https://acme.com", "PENDING", null, null, null, Instant.now()
        );
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void list_noFilter_returns200WithPage() throws Exception {
        when(applicationService.list(isNull(), any())).thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/admin/partner-applications"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void list_withStatusFilter_returns200() throws Exception {
        when(applicationService.list(eq(PartnerApplicationStatus.PENDING), any()))
            .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/admin/partner-applications").param("status", "PENDING"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void approve_returns200() throws Exception {
        UUID appId = UUID.randomUUID();
        when(applicationService.approve(eq(appId), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/admin/partner-applications/{id}/approve", appId))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void reject_returns200() throws Exception {
        UUID appId = UUID.randomUUID();
        // RejectApplicationRequest only has @NoArgsConstructor — use setter
        RejectApplicationRequest req = new RejectApplicationRequest();
        req.setReason("Violated TOS");
        when(applicationService.reject(eq(appId), any(), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/admin/partner-applications/{id}/reject", appId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());
    }
}
