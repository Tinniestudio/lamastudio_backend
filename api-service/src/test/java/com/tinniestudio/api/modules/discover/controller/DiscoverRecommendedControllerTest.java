package com.tinniestudio.api.modules.discover.controller;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.discover.service.DiscoverService;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DiscoverController.class)
@AutoConfigureMockMvc(addFilters = false)
class DiscoverRecommendedControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private DiscoverService discoverService;
    @MockBean  private JwtTokenProvider jwtTokenProvider;
    @MockBean  private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean  private UserDetailsServiceImpl userDetailsService;

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440001";

    private static final ContentSummaryResponse MOVIE = new ContentSummaryResponse(
        UUID.randomUUID(), "Interstellar", "interstellar", "Space odyssey",
        "MOVIE", "PUBLISHED", "PG", LocalDate.of(2014, 11, 7),
        false, false, 1500L, null, null
    );

    @Test
    @DisplayName("GET /discover/recommended returns 200 with recommendations for authenticated user")
    @WithMockUser(username = USER_ID, roles = "USER")
    void recommended_returnsRecommendations() throws Exception {
        when(discoverService.recommended(any(UUID.class))).thenReturn(List.of(MOVIE));

        mockMvc.perform(get("/discover/recommended").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].title").value("Interstellar"));
    }

    @Test
    @DisplayName("GET /discover/recommended falls back to trending for unauthenticated request")
    void recommended_fallsBackToTrendingWhenAnonymous() throws Exception {
        when(discoverService.trending(20)).thenReturn(List.of(MOVIE));

        mockMvc.perform(get("/discover/recommended").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }
}
