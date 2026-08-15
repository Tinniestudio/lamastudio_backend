package com.tinniestudio.api.modules.appeal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.appeal.dto.AppealResponse;
import com.tinniestudio.api.modules.appeal.dto.SubmitAppealRequest;
import com.tinniestudio.api.modules.appeal.service.AppealService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AppealController.class)
@AutoConfigureMockMvc(addFilters = false)
class AppealControllerTest {

    static final String USER_ID = "00000000-0000-0000-0000-000000000020";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AppealService appealService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    @Test
    @WithMockUser(username = USER_ID, roles = "USER")
    void submit_returns201() throws Exception {
        SubmitAppealRequest req = new SubmitAppealRequest();
        req.setReason("I was wrongly suspended");

        AppealResponse response = new AppealResponse(
            UUID.randomUUID(), UUID.fromString(USER_ID), "I was wrongly suspended",
            "PENDING", null, null, Instant.now()
        );
        when(appealService.submit(any(), any())).thenReturn(response);

        mockMvc.perform(post("/appeals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("PENDING"));
    }
}
