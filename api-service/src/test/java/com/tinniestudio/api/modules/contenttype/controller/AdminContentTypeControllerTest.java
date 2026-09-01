package com.tinniestudio.api.modules.contenttype.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.contenttype.dto.CreateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.dto.UpdateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.service.ContentTypeService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminContentTypeController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminContentTypeControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean ContentTypeService contentTypeService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    ContentTypeResponse sample() {
        return new ContentTypeResponse(UUID.randomUUID(), "Movie", "movie", "SINGLE_VIDEO", 0, true);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listAll_returns200() throws Exception {
        when(contentTypeService.listAll()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/admin/content-types"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].slug").value("movie"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_returns201() throws Exception {
        var req = new CreateContentTypeRequest("Documentary", "A documentary film", StructuralKind.SINGLE_VIDEO, 2);
        when(contentTypeService.create(any())).thenReturn(sample());

        mockMvc.perform(post("/admin/content-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_duplicateName_returns409() throws Exception {
        var req = new CreateContentTypeRequest("Movie", null, StructuralKind.SINGLE_VIDEO, 0);
        when(contentTypeService.create(any()))
            .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Content type name already exists: Movie"));

        mockMvc.perform(post("/admin/content-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_missingId_returns404() throws Exception {
        UUID missingId = UUID.randomUUID();
        var req = new UpdateContentTypeRequest("X", null, null, null, null);
        when(contentTypeService.update(org.mockito.ArgumentMatchers.eq(missingId), any()))
            .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Content type not found: " + missingId));

        mockMvc.perform(patch("/admin/content-types/{id}", missingId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_returns200() throws Exception {
        mockMvc.perform(delete("/admin/content-types/{id}", UUID.randomUUID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Content type deleted successfully"));
    }
}
