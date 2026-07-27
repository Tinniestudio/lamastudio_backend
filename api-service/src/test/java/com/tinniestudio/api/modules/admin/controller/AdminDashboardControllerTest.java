package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.admin.dto.AdminDashboardResponse;
import com.tinniestudio.api.modules.admin.service.AdminDashboardService;
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

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminDashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminDashboardControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AdminDashboardService dashboardService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void getDashboard_returns200WithStats() throws Exception {
        when(dashboardService.getDashboard()).thenReturn(
            new AdminDashboardResponse(500L, 12L, 200L, new BigDecimal("5000.00"), 3L, 2L, 1_000_000L, Map.of())
        );

        mockMvc.perform(get("/admin/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalUsers").value(500))
            .andExpect(jsonPath("$.data.activeSubscriptions").value(200));
    }
}
