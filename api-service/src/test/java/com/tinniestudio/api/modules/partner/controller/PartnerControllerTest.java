package com.tinniestudio.api.modules.partner.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.service.PartnerApplicationService;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.partner.dto.*;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import com.tinniestudio.api.modules.partner.service.PartnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PartnerController.class)
@AutoConfigureMockMvc(addFilters = false)
class PartnerControllerTest {

    static final String PARTNER_ID = "00000000-0000-0000-0000-000000000010";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean PartnerService partnerService;
    @MockBean PartnerApplicationService applicationService;
    @MockBean ContentRepository contentRepository;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    PartnerProfileResponse sampleProfile() {
        return new PartnerProfileResponse(
            UUID.randomUUID(),
            UUID.fromString(PARTNER_ID),
            "Acme Corp",
            "https://acme.com",
            "We make great content",
            "https://cdn.test/logo.png",
            BigDecimal.valueOf(70),
            true
        );
    }

    PartnerApplicationResponse sampleApplication() {
        return new PartnerApplicationResponse(
            UUID.randomUUID(),
            UUID.fromString(PARTNER_ID),
            "Acme Corp",
            "We make great content",
            "https://acme.com",
            "PENDING",
            null,
            null,
            null,
            Instant.now()
        );
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void apply_returns201() throws Exception {
        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme Corp");
        req.setDescription("We make great content");
        req.setWebsiteUrl("https://acme.com");

        when(applicationService.apply(any(), any())).thenReturn(sampleApplication());

        mockMvc.perform(post("/partners/applications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.companyName").value("Acme Corp"));
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void getProfile_returns200() throws Exception {
        when(partnerService.getProfile(any())).thenReturn(sampleProfile());

        mockMvc.perform(get("/partners/profile"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.companyName").value("Acme Corp"));
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void updateProfile_returns200() throws Exception {
        UpdatePartnerProfileRequest req = new UpdatePartnerProfileRequest();
        req.setCompanyName("Updated Corp");
        req.setBio("New bio");

        when(partnerService.updateProfile(any(), any())).thenReturn(sampleProfile());

        mockMvc.perform(patch("/partners/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.companyName").isString());
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void uploadLogo_returns200() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", "fake-png".getBytes());
        when(partnerService.uploadLogo(any(), any())).thenReturn("https://cdn.test/logo.png");
        when(partnerService.getProfile(any())).thenReturn(sampleProfile());

        mockMvc.perform(multipart("/partners/profile/logo").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.companyName").value("Acme Corp"));
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void getDashboard_returns200() throws Exception {
        PartnerDashboardResponse dashboard = new PartnerDashboardResponse(5L, 2L, 1L, 1000L, List.of());
        when(partnerService.getDashboard(any())).thenReturn(dashboard);

        mockMvc.perform(get("/partners/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.publishedContentCount").value(5));
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void getUploads_returns200() throws Exception {
        PartnerUploadSummaryResponse upload = new PartnerUploadSummaryResponse(
            UUID.randomUUID(), "VIDEO", UUID.randomUUID(), "path/to/file",
            "video.mp4", "COMPLETED", 1024L, Instant.now()
        );
        when(partnerService.getUploads(any(), any())).thenReturn(new PageImpl<>(List.of(upload)));

        mockMvc.perform(get("/partners/uploads"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void getContents_returns200() throws Exception {
        when(contentRepository.findByCreatedByOrderByCreatedAtDesc(any(), any()))
            .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/partners/contents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }
}
