package com.tinniestudio.api.modules.library.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.library.dto.FavoriteResponse;
import com.tinniestudio.api.modules.library.service.FavoriteService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = FavoriteController.class)
@AutoConfigureMockMvc(addFilters = false)
class FavoriteControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private FavoriteService favoriteService;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    private static final String CONTEXT_PATH = "/api/v1";
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private MockHttpServletRequestBuilder getWithContext(String path) {
        return get(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private MockHttpServletRequestBuilder postWithContext(String path) {
        return post(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private MockHttpServletRequestBuilder deleteWithContext(String path) {
        return delete(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    @Test
    @DisplayName("GET /favorites returns 200 with paginated favorites")
    @WithMockUser(username = USER_ID, roles = "USER")
    void listFavorites_returns200WithPaginatedFavorites() throws Exception {
        UUID contentId = UUID.randomUUID();
        ContentSummaryResponse contentSummary = new ContentSummaryResponse(
            contentId, "Test Movie", "test-movie", "A test movie",
            "MOVIE", "PUBLISHED", "PG",
            LocalDate.of(2024, 1, 1), false, false,
            100L, BigDecimal.ZERO, 0, "http://cdn.test/poster.jpg", "http://cdn.test/thumbnail.jpg"
        );
        FavoriteResponse favResponse = new FavoriteResponse(
            UUID.randomUUID(), contentId, Instant.now(), contentSummary
        );

        when(favoriteService.list(any(UUID.class), any()))
            .thenReturn(new PageImpl<>(List.of(favResponse)));

        mockMvc.perform(getWithContext("/favorites"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].contentId").value(contentId.toString()));
    }

    @Test
    @DisplayName("POST /favorites/{contentId} returns 201 Created")
    @WithMockUser(username = USER_ID, roles = "USER")
    void addFavorite_returns201() throws Exception {
        UUID contentId = UUID.randomUUID();
        doNothing().when(favoriteService).add(any(UUID.class), any(UUID.class));

        mockMvc.perform(postWithContext("/favorites/" + contentId))
            .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("DELETE /favorites/{contentId} returns 204 No Content")
    @WithMockUser(username = USER_ID, roles = "USER")
    void removeFavorite_returns204() throws Exception {
        UUID contentId = UUID.randomUUID();
        doNothing().when(favoriteService).remove(any(UUID.class), any(UUID.class));

        mockMvc.perform(deleteWithContext("/favorites/" + contentId))
            .andExpect(status().isNoContent());
    }
}
