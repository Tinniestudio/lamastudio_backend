package com.tinniestudio.api.modules.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.admin.dto.RejectAppealRequest;
import com.tinniestudio.api.modules.appeal.dto.AppealResponse;
import com.tinniestudio.api.modules.appeal.service.AppealService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.AppealStatus;
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

@WebMvcTest(controllers = AdminAppealController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminAppealControllerTest {

    static final String ADMIN_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AppealService appealService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    AppealResponse sampleResponse(String status) {
        return new AppealResponse(
            UUID.randomUUID(), UUID.randomUUID(), "I was wrongly suspended",
            status, null, null, Instant.now()
        );
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void list_noFilter_returns200WithPage() throws Exception {
        when(appealService.list(isNull(), any())).thenReturn(new PageImpl<>(List.of(sampleResponse("PENDING"))));

        mockMvc.perform(get("/admin/appeals"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void list_withStatusFilter_returns200() throws Exception {
        when(appealService.list(eq(AppealStatus.PENDING), any()))
            .thenReturn(new PageImpl<>(List.of(sampleResponse("PENDING"))));

        mockMvc.perform(get("/admin/appeals").param("status", "PENDING"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void approve_returns200() throws Exception {
        UUID appealId = UUID.randomUUID();
        when(appealService.approve(eq(appealId), any())).thenReturn(sampleResponse("APPROVED"));

        mockMvc.perform(post("/admin/appeals/{id}/approve", appealId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void reject_returns200() throws Exception {
        UUID appealId = UUID.randomUUID();
        RejectAppealRequest req = new RejectAppealRequest();
        req.setReason("Insufficient grounds");
        when(appealService.reject(eq(appealId), any(), any())).thenReturn(sampleResponse("REJECTED"));

        mockMvc.perform(post("/admin/appeals/{id}/reject", appealId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }
}
