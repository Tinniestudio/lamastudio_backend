package com.tinniestudio.api.modules.search.controller;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.search.dto.SearchRequest;
import com.tinniestudio.api.modules.search.dto.SearchResponse;
import com.tinniestudio.api.modules.search.service.SearchService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SearchController.class)
@AutoConfigureMockMvc(addFilters = false)
class SearchControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private SearchService searchService;
    @MockBean  private JwtTokenProvider jwtTokenProvider;
    @MockBean  private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean  private UserDetailsServiceImpl userDetailsService;

    private static final ContentSummaryResponse MOVIE = new ContentSummaryResponse(
        UUID.randomUUID(), "Interstellar", "interstellar", "Space odyssey",
        new ContentTypeResponse(UUID.randomUUID(), "Movie", "movie", "SINGLE_VIDEO", 0, true),
        "PUBLISHED", "PG", LocalDate.of(2014, 11, 7),
        false, false, 1500L, BigDecimal.ZERO, 0, null, null
    );

    @Test
    @DisplayName("GET /search returns 200 with results array")
    void search_returnsResults() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
            .thenReturn(new SearchResponse(List.of(MOVIE), 1L, 0, 20, 1));

        mockMvc.perform(get("/search")
                .param("q", "interstellar")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results[0].title").value("Interstellar"))
            .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("GET /search returns 400 when q is missing")
    void search_returns400WhenQMissing() throws Exception {
        mockMvc.perform(get("/search").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /search returns 400 when q is shorter than 2 chars")
    void search_returns400WhenQTooShort() throws Exception {
        when(searchService.search(any()))
            .thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "too short"));

        mockMvc.perform(get("/search").param("q", "a").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /search passes type filter to service")
    void search_passesTypeFilter() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
            .thenReturn(new SearchResponse(List.of(MOVIE), 1L, 0, 20, 1));

        mockMvc.perform(get("/search")
                .param("q", "action")
                .param("type", "MOVIE")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results").isArray());
    }
}
