package com.tinniestudio.api.modules.playback.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.playback.dto.*;
import com.tinniestudio.api.modules.playback.service.PlaybackService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PlaybackController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlaybackControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private PlaybackService playbackService;
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

    @Test
    @DisplayName("GET /playback/access/{contentId} returns 200 with hasAccess: true when access granted")
    @WithMockUser(username = USER_ID, roles = "USER")
    void checkAccess_returnsAccessGranted() throws Exception {
        UUID contentId = UUID.randomUUID();
        when(playbackService.checkAccess(any(org.springframework.security.core.userdetails.UserDetails.class), any(UUID.class)))
            .thenReturn(AccessCheckResponse.granted());

        mockMvc.perform(getWithContext("/playback/access/" + contentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hasAccess").value(true));
    }

    @Test
    @DisplayName("GET /playback/manifest/content/{contentId} returns 200 with a manifest URL")
    @WithMockUser(username = USER_ID, roles = "USER")
    void getContentManifest_returnsManifest() throws Exception {
        UUID contentId = UUID.randomUUID();
        PlaybackManifestResponse manifest = new PlaybackManifestResponse(
            "http://cdn.test/manifest.m3u8", List.of(), 120, 3600);
        when(playbackService.getContentManifest(any(org.springframework.security.core.userdetails.UserDetails.class), any(UUID.class)))
            .thenReturn(manifest);

        mockMvc.perform(getWithContext("/playback/manifest/content/" + contentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manifestUrl").value("http://cdn.test/manifest.m3u8"));
    }

    @Test
    @DisplayName("GET /playback/manifest/episode/{episodeId} returns 200 with manifest URL")
    @WithMockUser(username = USER_ID, roles = "USER")
    void getEpisodeManifest_returnsManifest() throws Exception {
        UUID episodeId = UUID.randomUUID();
        PlaybackManifestResponse manifest = new PlaybackManifestResponse(
            "http://cdn.test/episode.m3u8", List.of(), null, 1800);
        when(playbackService.getEpisodeManifest(any(org.springframework.security.core.userdetails.UserDetails.class), any(UUID.class)))
            .thenReturn(manifest);

        mockMvc.perform(getWithContext("/playback/manifest/episode/" + episodeId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manifestUrl").value("http://cdn.test/episode.m3u8"));
    }

    @Test
    @DisplayName("GET /playback/manifest/content/{contentId}/trailer returns 200 with no auth required")
    void getTrailerManifest_returnsManifestWithoutAuth() throws Exception {
        UUID contentId = UUID.randomUUID();
        PlaybackManifestResponse manifest = new PlaybackManifestResponse(
            "http://cdn.test/trailer.m3u8", List.of(), null, 90);
        when(playbackService.getTrailerManifest(any(UUID.class)))
            .thenReturn(manifest);

        // Deliberately no @WithMockUser — proves this endpoint needs no authentication.
        mockMvc.perform(getWithContext("/playback/manifest/content/" + contentId + "/trailer"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manifestUrl").value("http://cdn.test/trailer.m3u8"))
            .andExpect(jsonPath("$.data.resumeAt").doesNotExist());
    }

    @Test
    @DisplayName("POST /playback/progress returns 204 No Content")
    @WithMockUser(username = USER_ID, roles = "USER")
    void recordProgress_returns200() throws Exception {
        ProgressRequest req = new ProgressRequest();
        req.setContentId(UUID.randomUUID());
        req.setProgressSeconds(300);
        req.setDurationSeconds(3600);

        doNothing().when(playbackService).recordProgress(any(UUID.class), any(ProgressRequest.class));

        mockMvc.perform(postWithContext("/playback/progress")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Progress recorded successfully"));
    }

    @Test
    @DisplayName("GET /playback/continue-watching returns 200 with an array")
    @WithMockUser(username = USER_ID, roles = "USER")
    void getContinueWatching_returnsArray() throws Exception {
        ContinueWatchingItem item = new ContinueWatchingItem(
            UUID.randomUUID(), null, "My Movie", null,
            300, 3600, new BigDecimal("8.33"), Instant.now());
        when(playbackService.getContinueWatching(any(UUID.class)))
            .thenReturn(List.of(item));

        mockMvc.perform(getWithContext("/playback/continue-watching"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[0].title").value("My Movie"));
    }
}
