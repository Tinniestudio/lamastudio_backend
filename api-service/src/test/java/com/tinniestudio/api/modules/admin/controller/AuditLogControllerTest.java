package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import com.tinniestudio.api.modules.admin.service.AuditLogService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuditLogController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuditLogControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AuditLogService auditLogService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    // AuditLogResponse fields: id, actorId, actorType, action, targetType, targetId, reason, metadata, createdAt
    AuditLogResponse sampleLog() {
        return new AuditLogResponse(UUID.randomUUID(), UUID.randomUUID(), "ADMIN",
            "USER_STATUS_CHANGED", "USER", UUID.randomUUID(), "Suspended", null, Instant.now());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listAll_returns200WithPage() throws Exception {
        when(auditLogService.listAll(any())).thenReturn(new PageImpl<>(List.of(sampleLog())));

        mockMvc.perform(get("/admin/audit-logs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listByTarget_returns200() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(auditLogService.listByTarget(eq("USER"), eq(targetId), any()))
            .thenReturn(new PageImpl<>(List.of(sampleLog())));

        mockMvc.perform(get("/admin/audit-logs/target/USER/{id}", targetId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());
    }
}
