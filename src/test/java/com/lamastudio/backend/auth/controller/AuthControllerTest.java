package com.lamastudio.backend.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lamastudio.backend.auth.dto.*;
import com.lamastudio.backend.auth.service.AuthService;
import com.lamastudio.backend.exception.BadCredentialsException;
import com.lamastudio.backend.auth.jwt.JwtTokenProvider;
import com.lamastudio.backend.auth.jwt.JwtAuthenticationFilter;
import com.lamastudio.backend.user.service.UserDetailsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    // Mock security components to avoid loading full security stack in slice test
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    private static final String CONTEXT_PATH = "/api/v1";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postWithContext(String path) {
        return post(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    @Test
    @DisplayName("POST /auth/register returns 201 on success")
    void register_success() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("new@example.com");
        req.setPassword("Password1!");
        req.setFirstName("Jane");
        req.setLastName("Doe");

        AuthResponse response = AuthResponse.builder()
                .userId(UUID.randomUUID())
                .email(req.getEmail())
                .roles(Set.of("ROLE_USER"))
                .provider("LOCAL")
                .emailVerified(false)
                .message("Registration successful")
                .build();

        when(authService.register(any(RegisterRequest.class), any())).thenReturn(response);

    mockMvc.perform(postWithContext("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(req.getEmail()))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    @Test
    @DisplayName("POST /auth/register fails validation when payload missing")
    void register_validation() throws Exception {
    mockMvc.perform(postWithContext("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /auth/login returns 200 on success")
    void login_success() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("user@example.com");
        req.setPassword("Password1!");

        AuthResponse response = AuthResponse.builder()
                .userId(UUID.randomUUID())
                .email(req.getEmail())
                .roles(Set.of("ROLE_USER"))
                .provider("LOCAL")
                .emailVerified(true)
                .build();

        when(authService.login(any(LoginRequest.class), any())).thenReturn(response);

    mockMvc.perform(postWithContext("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(req.getEmail()))
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    @DisplayName("POST /auth/login returns 401 on BadCredentialsException")
    void login_badCredentials() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("user@example.com");
        req.setPassword("bad");

        when(authService.login(any(LoginRequest.class), any())).thenThrow(new BadCredentialsException("Invalid email or password"));

    mockMvc.perform(postWithContext("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /auth/forgot-password returns 200")
    void forgotPassword() throws Exception {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("user@example.com");
        doNothing().when(authService).forgotPassword(any(ForgotPasswordRequest.class));

    mockMvc.perform(postWithContext("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /auth/reset-password returns 200")
    void resetPassword() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("token");
        req.setNewPassword("Password1!");
        doNothing().when(authService).resetPassword(any(ResetPasswordRequest.class));

    mockMvc.perform(patch("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }
}
