package com.tinniestudio.api.modules.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.service.NotificationService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

    static final String USER_ID = "00000000-0000-0000-0000-000000000002";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean NotificationService notificationService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    NotificationResponse sampleNotif() {
        return new NotificationResponse(UUID.randomUUID(), "CONTENT_PROCESSED",
            "Ready", "Done", "IN_APP", false, null, null, null, Instant.now());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void list_returns200() throws Exception {
        when(notificationService.listForUser(any(), any())).thenReturn(new PageImpl<>(List.of(sampleNotif())));
        mockMvc.perform(get("/notifications"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void unreadCount_returns200() throws Exception {
        when(notificationService.getUnreadCount(any())).thenReturn(3L);
        mockMvc.perform(get("/notifications/unread-count"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    @WithMockUser(username = USER_ID)
    void markRead_returns204() throws Exception {
        mockMvc.perform(post("/notifications/{id}/read", UUID.randomUUID()))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void markAllRead_returns204() throws Exception {
        mockMvc.perform(post("/notifications/read-all"))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void getPreferences_returns200() throws Exception {
        when(notificationService.getPreferences(any())).thenReturn(List.of());
        mockMvc.perform(get("/notifications/preferences"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void updatePreference_returns200() throws Exception {
        var req = new UpdatePreferenceRequest();
        req.setChannel(NotificationChannel.IN_APP);
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setIsEnabled(false);

        when(notificationService.updatePreference(any(), any()))
            .thenReturn(new NotificationPreferenceResponse(UUID.randomUUID(), "IN_APP", "CONTENT_PROCESSED", false));

        mockMvc.perform(put("/notifications/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());
    }
}
