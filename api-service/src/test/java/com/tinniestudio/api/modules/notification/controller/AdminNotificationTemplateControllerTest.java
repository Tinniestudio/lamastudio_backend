package com.tinniestudio.api.modules.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.service.NotificationTemplateService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminNotificationTemplateController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminNotificationTemplateControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean NotificationTemplateService templateService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    NotificationTemplateResponse sample() {
        return new NotificationTemplateResponse(UUID.randomUUID(), "CONTENT_PROCESSED",
            "Ready", "Your content is ready", "IN_APP", true, Instant.now(), Instant.now());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_returns200() throws Exception {
        when(templateService.list()).thenReturn(List.of(sample()));
        mockMvc.perform(get("/admin/notification-templates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].eventType").value("CONTENT_PROCESSED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_returns201() throws Exception {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("Ready"); req.setBodyTemplate("Done");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateService.create(any())).thenReturn(sample());

        mockMvc.perform(post("/admin/notification-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_returns200() throws Exception {
        mockMvc.perform(delete("/admin/notification-templates/{id}", UUID.randomUUID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Notification template deleted successfully"));
    }
}
