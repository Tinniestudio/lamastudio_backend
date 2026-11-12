package com.tinniestudio.api.auth.controller;

import com.tinniestudio.api.modules.auth.controller.SessionController;
import com.tinniestudio.api.modules.auth.user.service.AuthProfileService;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SessionController.class)
@AutoConfigureMockMvc(addFilters = false)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private SessionService sessionService;
    @MockBean private UserRepository userRepository;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;
    @MockBean private AuthProfileService authProfileService;

    private static final String CONTEXT_PATH = "/api/v1";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /auth/sessions/{id}/revoke delegates to sessionService.selfRevokeSession and returns 200")
    void revoke_returnsOk() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        doNothing().when(sessionService).selfRevokeSession(eq(userId), eq(sessionId), any());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new User(userId.toString(), "n/a", List.of()), null, List.of()));

        mockMvc.perform(post(CONTEXT_PATH + "/auth/sessions/" + sessionId + "/revoke")
                        .contextPath(CONTEXT_PATH))
                .andExpect(status().isOk());
    }
}
