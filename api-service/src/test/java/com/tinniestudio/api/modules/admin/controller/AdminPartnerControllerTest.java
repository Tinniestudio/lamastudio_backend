package com.tinniestudio.api.modules.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.partner.dto.AdminUpdatePartnerProfileRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerProfileResponse;
import com.tinniestudio.api.modules.partner.service.PartnerService;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminPartnerController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminPartnerControllerTest {

    static final String ADMIN_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean PartnerService partnerService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    PartnerProfileResponse sampleProfile(boolean verified) {
        return new PartnerProfileResponse(
            UUID.randomUUID(), UUID.randomUUID(), "Acme Corp", "https://acme.com",
            "bio", "https://cdn.test/logo.png", BigDecimal.valueOf(70), verified
        );
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void getProfile_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        when(partnerService.getProfile(userId)).thenReturn(sampleProfile(true));

        mockMvc.perform(get("/admin/partners/{userId}", userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.companyName").value("Acme Corp"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void updateProfile_unverify_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        AdminUpdatePartnerProfileRequest req = new AdminUpdatePartnerProfileRequest();
        req.setIsVerified(false);

        when(partnerService.adminUpdateProfile(eq(userId), any(), any())).thenReturn(sampleProfile(false));

        mockMvc.perform(patch("/admin/partners/{userId}", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.isVerified").value(false));
    }
}
