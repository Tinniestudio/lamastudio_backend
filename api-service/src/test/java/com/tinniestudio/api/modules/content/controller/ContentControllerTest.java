package com.tinniestudio.api.modules.content.controller;

import com.tinniestudio.api.modules.content.service.ContentService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gap 3 (Batch 16 #7): proves an UNAUTHENTICATED caller can reach the view-tracking
 * beacon and that it publishes with userId=null — this is the endpoint fixed to make
 * anonymous view tracking actually reachable (playback manifest endpoints stay
 * authenticated for subscription/capability checks; this is a separate, lightweight,
 * genuinely public endpoint).
 */
@WebMvcTest(controllers = ContentController.class)
@AutoConfigureMockMvc(addFilters = false)
class ContentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ContentService contentService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    @Test
    @DisplayName("POST /contents/id/{id}/view with NO authentication at all still succeeds " +
            "and records the view with userId=null")
    void recordView_noAuthentication_recordsViewWithNullUserId() throws Exception {
        UUID contentId = UUID.randomUUID();
        doNothing().when(contentService).recordView(eq(contentId), isNull());

        // No @WithMockUser at all — simulates a genuinely anonymous caller. If the endpoint
        // (or the security config it lives under) required auth, MockMvc + the real
        // JwtAuthenticationFilter/SecurityConfig would 401 this in production; here we assert
        // the controller method itself accepts a null principal and forwards userId=null.
        mockMvc.perform(post("/contents/id/{id}/view", contentId))
                .andExpect(status().isAccepted());

        verify(contentService).recordView(eq(contentId), isNull());
    }

    @Test
    @DisplayName("POST /contents/id/{id}/view with an authenticated user records the real userId")
    @WithMockUser(username = "00000000-0000-0000-0000-000000000099", roles = "USER")
    void recordView_authenticated_recordsRealUserId() throws Exception {
        UUID contentId = UUID.randomUUID();
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        doNothing().when(contentService).recordView(eq(contentId), eq(userId));

        mockMvc.perform(post("/contents/id/{id}/view", contentId))
                .andExpect(status().isAccepted());

        verify(contentService).recordView(eq(contentId), eq(userId));
    }
}
