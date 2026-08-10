package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminUploadController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminUploadControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean VideoAssetRepository videoAssetRepository;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void getProcessing_returns200WithPage() throws Exception {
        when(videoAssetRepository.findByProcessingStatusInOrderByCreatedAtDesc(any(), any()))
            .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/admin/uploads/processing"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }
}
