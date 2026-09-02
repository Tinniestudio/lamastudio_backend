package com.tinniestudio.api.modules.library.controller;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.library.dto.WatchHistoryResponse;
import com.tinniestudio.api.modules.library.service.WatchHistoryService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HistoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class HistoryControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private WatchHistoryService historyService;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    private static final String CONTEXT_PATH = "/api/v1";
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private MockHttpServletRequestBuilder getWithContext(String path) {
        return get(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private MockHttpServletRequestBuilder deleteWithContext(String path) {
        return delete(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    @Test
    @DisplayName("GET /history returns 200 with paginated watch history")
    @WithMockUser(username = USER_ID, roles = "USER")
    void listHistory_returns200WithPaginatedHistory() throws Exception {
        UUID contentId = UUID.randomUUID();
        ContentSummaryResponse contentSummary = new ContentSummaryResponse(
            contentId, "Test Movie", "test-movie", "A test movie",
            new ContentTypeResponse(UUID.randomUUID(), "Movie", "movie", "SINGLE_VIDEO", 0, true),
            "PUBLISHED", "PG",
            LocalDate.of(2024, 1, 1), false, false,
            100L, BigDecimal.ZERO, 0, "http://cdn.test/poster.jpg", "http://cdn.test/thumbnail.jpg"
        );
        WatchHistoryResponse histResponse = new WatchHistoryResponse(
            UUID.randomUUID(), contentId, null, Instant.now(),
            300, 3600, "WEB", contentSummary
        );

        when(historyService.list(any(UUID.class), any()))
            .thenReturn(new PageImpl<>(List.of(histResponse)));

        mockMvc.perform(getWithContext("/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].contentId").value(contentId.toString()));
    }

    @Test
    @DisplayName("DELETE /history/{id} returns 200 with confirmation message")
    @WithMockUser(username = USER_ID, roles = "USER")
    void deleteHistoryEntry_returns200() throws Exception {
        UUID historyId = UUID.randomUUID();
        doNothing().when(historyService).delete(any(UUID.class), any(UUID.class));

        mockMvc.perform(deleteWithContext("/history/" + historyId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Watch history entry deleted successfully"));
    }

    @Test
    @DisplayName("DELETE /history returns 200 with confirmation message (clear all)")
    @WithMockUser(username = USER_ID, roles = "USER")
    void deleteAllHistory_returns200() throws Exception {
        doNothing().when(historyService).deleteAll(any(UUID.class));

        mockMvc.perform(deleteWithContext("/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Watch history cleared successfully"));
    }
}
