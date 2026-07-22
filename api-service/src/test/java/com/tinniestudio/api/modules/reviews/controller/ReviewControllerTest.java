package com.tinniestudio.api.modules.reviews.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.reviews.dto.CreateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewRequest;
import com.tinniestudio.api.modules.reviews.service.ReviewService;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private ReviewService reviewService;
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

    private MockHttpServletRequestBuilder patchWithContext(String path) {
        return patch(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private MockHttpServletRequestBuilder deleteWithContext(String path) {
        return delete(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    @Test
    @DisplayName("GET /contents/{contentId}/reviews returns 200 with paginated reviews (public)")
    void listReviews_returns200WithPaginatedReviews() throws Exception {
        UUID contentId = UUID.randomUUID();
        ReviewResponse reviewResponse = new ReviewResponse(
            UUID.randomUUID(), contentId, UUID.fromString(USER_ID),
            (short) 4, "Great content!", "APPROVED",
            Instant.now(), Instant.now()
        );

        when(reviewService.list(any(UUID.class), any()))
            .thenReturn(new PageImpl<>(List.of(reviewResponse)));

        mockMvc.perform(getWithContext("/contents/" + contentId + "/reviews"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].rating").value(4));
    }

    @Test
    @DisplayName("POST /contents/{contentId}/reviews returns 201 with review body")
    @WithMockUser(username = USER_ID, roles = "USER")
    void createReview_returns201WithBody() throws Exception {
        UUID contentId = UUID.randomUUID();
        ReviewResponse reviewResponse = new ReviewResponse(
            UUID.randomUUID(), contentId, UUID.fromString(USER_ID),
            (short) 5, "Amazing!", "PENDING",
            Instant.now(), Instant.now()
        );

        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating((short) 5);
        request.setBody("Amazing!");

        when(reviewService.create(any(UUID.class), any(UUID.class), any(CreateReviewRequest.class)))
            .thenReturn(reviewResponse);

        mockMvc.perform(postWithContext("/contents/" + contentId + "/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.rating").value(5));
    }

    @Test
    @DisplayName("PATCH /reviews/{id} returns 200 with updated review body")
    @WithMockUser(username = USER_ID, roles = "USER")
    void updateReview_returns200WithBody() throws Exception {
        UUID reviewId = UUID.randomUUID();
        ReviewResponse reviewResponse = new ReviewResponse(
            reviewId, UUID.randomUUID(), UUID.fromString(USER_ID),
            (short) 3, "Updated review", "PENDING",
            Instant.now(), Instant.now()
        );

        UpdateReviewRequest request = new UpdateReviewRequest();
        request.setRating((short) 3);
        request.setBody("Updated review");

        when(reviewService.update(any(UUID.class), any(UUID.class), any(UpdateReviewRequest.class)))
            .thenReturn(reviewResponse);

        mockMvc.perform(patchWithContext("/reviews/" + reviewId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rating").value(3));
    }

    @Test
    @DisplayName("DELETE /reviews/{id} returns 204 No Content")
    @WithMockUser(username = USER_ID, roles = "USER")
    void deleteReview_returns204() throws Exception {
        UUID reviewId = UUID.randomUUID();
        doNothing().when(reviewService).delete(any(UUID.class), any(UUID.class));

        mockMvc.perform(deleteWithContext("/reviews/" + reviewId))
            .andExpect(status().isNoContent());
    }
}
