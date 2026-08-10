package com.tinniestudio.api.modules.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.admin.dto.AdminUserResponse;
import com.tinniestudio.api.modules.admin.dto.PromoteToPartnerRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.modules.admin.service.AdminUserService;
import com.tinniestudio.api.modules.partner.dto.PartnerProfileResponse;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminUserController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminUserControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AdminUserService adminUserService;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    private static final String CONTEXT_PATH = "/api/v1";
    private static final String ADMIN_ID = "550e8400-e29b-41d4-a716-446655440000";

    private MockHttpServletRequestBuilder getWithContext(String path) {
        return get(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private MockHttpServletRequestBuilder patchWithContext(String path) {
        return patch(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private MockHttpServletRequestBuilder deleteWithContext(String path) {
        return delete(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private MockHttpServletRequestBuilder postWithContext(String path) {
        return post(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private AdminUserResponse sampleUser(UUID id) {
        return new AdminUserResponse(id, "test@example.com", "ACTIVE", Set.of("ROLE_USER"), true, Instant.now());
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void listUsers_returns200WithPage() throws Exception {
        UUID id = UUID.randomUUID();
        when(adminUserService.listUsers(isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(sampleUser(id))));

        mockMvc.perform(getWithContext("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].email").value("test@example.com"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void getUserById_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(adminUserService.getById(id)).thenReturn(sampleUser(id));

        mockMvc.perform(getWithContext("/admin/users/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void updateUser_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateUserRequest req = new UpdateUserRequest();
        req.setEmail("new@example.com");
        when(adminUserService.update(eq(id), any())).thenReturn(sampleUser(id));

        mockMvc.perform(patchWithContext("/admin/users/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("test@example.com"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void updateUserStatus_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setStatus(AccountStatus.SUSPENDED);
        req.setReason("TOS violation");

        doNothing().when(adminUserService).updateStatus(any(), any(), any());

        mockMvc.perform(patchWithContext("/admin/users/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User status updated successfully"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void deleteUser_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(adminUserService).softDelete(any(), any());

        mockMvc.perform(deleteWithContext("/admin/users/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User deleted successfully"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void promoteToPartner_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        PromoteToPartnerRequest req = new PromoteToPartnerRequest();
        req.setCompanyName("Acme Corp");

        PartnerProfileResponse profile = new PartnerProfileResponse(
            UUID.randomUUID(), id, "Acme Corp", null, null, null, BigDecimal.valueOf(70), true
        );
        when(adminUserService.promoteToPartner(eq(id), any(), any())).thenReturn(profile);

        mockMvc.perform(postWithContext("/admin/users/" + id + "/promote-to-partner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyName").value("Acme Corp"));
    }
}
